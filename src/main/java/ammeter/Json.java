package ammeter;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/** JSON with snake_case keys (run_id, std_dev, ...) and paths as plain text, for the result archive. */
public final class Json {
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .addModule(new SimpleModule()
                    .addSerializer(Path.class, ToStringSerializer.instance)
                    .addDeserializer(Path.class, new JsonDeserializer<>() {
                        @Override
                        public Path deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                            return Path.of(p.getValueAsString());
                        }
                    }))
            .build();

    private static final ObjectWriter PRETTY = MAPPER.writer(new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator("")
                    .withArrayEmptySeparator(""))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n")));

    private Json() {
    }

    /** Indented, for files people read (run.json, comparison.json). */
    public static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One line, for index.jsonl and command-line arguments. */
    public static String compact(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }
}
