package ammeter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Number and value formatting that matches Python's {@code format()} and {@code repr()}, so reports and error
 * messages read the same as the original Python framework (e.g. {@code 0.0001234}, {@code 1e+16}, {@code 'abc'}).
 */
public final class Fmt {
    private Fmt() {
    }

    /** Like Python's {@code format(x, "g")}: 6 significant digits, trailing zeros dropped. */
    public static String g(double x) {
        return g(x, 6);
    }

    /** Like Python's {@code format(x, ".{precision}g")}. */
    public static String g(double x, int precision) {
        if (!Double.isFinite(x)) {
            return special(x, false);
        }
        int p = Math.max(precision, 1);
        String sign = x < 0 || isNegativeZero(x) ? "-" : "";
        if (x == 0) {
            return sign + "0";
        }
        BigDecimal d = new BigDecimal(Math.abs(x)).round(new MathContext(p, RoundingMode.HALF_EVEN));
        int exp = d.precision() - d.scale() - 1;
        if (-4 <= exp && exp < p) {
            return sign + stripZeros(d.setScale(Math.max(p - 1 - exp, 0), RoundingMode.HALF_EVEN).toPlainString());
        }
        return sign + scientific(d, exp);
    }

    /** Like Python's {@code format(x, "+.{precision}g")} when {@code signed}. */
    public static String g(double x, int precision, boolean signed) {
        String text = g(x, precision);
        return signed && !text.startsWith("-") ? "+" + text : text;
    }

    /** Like Python's {@code format(x, ".{decimals}f")}, with a leading + when {@code signed}. */
    public static String f(double x, int decimals, boolean signed) {
        if (!Double.isFinite(x)) {
            return special(x, signed);
        }
        String text = new BigDecimal(Math.abs(x)).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
        if (x < 0 || isNegativeZero(x)) {
            return "-" + text;
        }
        return (signed ? "+" : "") + text;
    }

    public static String f(double x, int decimals) {
        return f(x, decimals, false);
    }

    /** Like Python's {@code format(x, ".{decimals}%")}. */
    public static String percent(double x, int decimals, boolean signed) {
        if (!Double.isFinite(x)) {
            return special(x, signed) + "%";
        }
        return f(x * 100, decimals, signed) + "%";
    }

    public static String percent(double x, int decimals) {
        return percent(x, decimals, false);
    }

    /** Python's {@code str()}: text as it is, anything else like {@link #repr}. */
    public static String str(Object value) {
        return value instanceof String ? (String) value : repr(value);
    }

    /** Python's {@code repr()} of a value read from YAML or JSON: None, True, 1.5, 'text', [..], {..}. */
    public static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean) {
            return (Boolean) value ? "True" : "False";
        }
        if (value instanceof Double || value instanceof Float) {
            return repr(((Number) value).doubleValue());
        }
        if (value instanceof String) {
            return repr((String) value);
        }
        if (value instanceof List) {
            return ((List<?>) value).stream().map(Fmt::repr).collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Map) {
            return ((Map<?, ?>) value).entrySet().stream()
                    .map(e -> repr(e.getKey()) + ": " + repr(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return String.valueOf(value);
    }

    /** Python's {@code repr()} of a float: the shortest text that reads back as the same number. */
    public static String repr(double x) {
        if (!Double.isFinite(x)) {
            return special(x, false);
        }
        if (x == 0) {
            return isNegativeZero(x) ? "-0.0" : "0.0";
        }
        String sign = x < 0 ? "-" : "";
        BigDecimal d = new BigDecimal(Double.toString(Math.abs(x))).stripTrailingZeros();
        int exp = d.precision() - d.scale() - 1;
        if (-4 <= exp && exp < 16) {
            String text = d.toPlainString();
            return sign + (text.contains(".") ? text : text + ".0");
        }
        return sign + scientific(d, exp);
    }

    /** Python's {@code repr()} of a string: in single quotes (double if it contains only single ones). */
    public static String repr(String s) {
        char quote = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder().append(quote);
        s.codePoints().forEach(c -> {
            if (c == '\\' || c == quote) {
                out.append('\\').appendCodePoint(c);
            } else if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (c == '\t') {
                out.append("\\t");
            } else if (c < 0x20 || (c >= 0x7f && c < 0xa0)) {
                out.append(String.format("\\x%02x", c));
            } else if (Character.getType(c) == Character.SURROGATE || Character.getType(c) == Character.UNASSIGNED) {
                out.append(c <= 0xffff ? String.format("\\u%04x", c) : String.format("\\U%08x", c));
            } else {
                out.appendCodePoint(c);
            }
        });
        return out.append(quote).toString();
    }

    /** Python's {@code type(value).__name__} for values read from YAML. */
    public static String typeName(Object value) {
        if (value == null) {
            return "NoneType";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Double || value instanceof Float) {
            return "float";
        }
        if (value instanceof Number) {
            return "int";
        }
        if (value instanceof String) {
            return "str";
        }
        if (value instanceof List) {
            return "list";
        }
        if (value instanceof Map) {
            return "dict";
        }
        return value.getClass().getSimpleName();
    }

    private static String scientific(BigDecimal d, int exp) {
        String digits = d.unscaledValue().toString().replaceAll("0+$", "");
        String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        return mantissa + "e" + (exp < 0 ? "-" : "+") + String.format("%02d", Math.abs(exp));
    }

    private static String stripZeros(String text) {
        return text.contains(".") ? text.replaceAll("\\.?0+$", "") : text;
    }

    private static String special(double x, boolean signed) {
        if (Double.isNaN(x)) {
            return signed ? "+nan" : "nan";
        }
        return x < 0 ? "-inf" : signed ? "+inf" : "inf";
    }

    private static boolean isNegativeZero(double x) {
        return Double.doubleToRawLongBits(x) == Long.MIN_VALUE;
    }
}
