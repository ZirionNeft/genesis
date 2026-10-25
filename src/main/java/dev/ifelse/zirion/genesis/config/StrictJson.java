package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Adapter around the Gson supplied by the pinned Hytale server; never imported by Core. */
final class StrictJson {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_DEPTH = 64;

    private StrictJson() {}

    static JsonObject read(InputStream input, String source) {
        if (input == null) throw error("INPUT", source + "#", "Required document is unavailable");
        try (input) {
            return parse(input.readNBytes(MAX_BYTES + 1), source);
        } catch (IOException exception) {
            throw error("INPUT", source + "#", "Cannot read document");
        }
    }

    static JsonObject parse(byte[] bytes, String source) {
        if (bytes.length > MAX_BYTES) throw error("LIMIT", source + "#", "Document exceeds 2 MiB");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw error("INPUT", source + "#", "Invalid UTF-8");
        }
        if (text.startsWith("\ufeff")) throw error("INPUT", source + "#", "UTF-8 BOM is unsupported");
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = readValue(reader, source + "#", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw error("JSON", source + "#", "Trailing JSON data");
            return new JsonNode(root, source + "#").object();
        } catch (IOException | NumberFormatException exception) {
            throw error("JSON", source + "#", "Invalid JSON syntax or number");
        }
    }

    private static JsonElement readValue(JsonReader reader, String path, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw error("LIMIT", path, "JSON nesting exceeds 64 levels");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                var object = new JsonObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    unicode(name, path);
                    String child = path + "/" + JsonNode.escape(name);
                    if (object.has(name)) throw error("DUPLICATE", child, "Duplicate JSON key");
                    object.add(name, readValue(reader, child, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                var array = new JsonArray();
                while (reader.hasNext()) array.add(readValue(reader, path + "/" + array.size(), depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> {
                String value = reader.nextString(); unicode(value, path); yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String value = reader.nextString();
                if (value.length() > 128) throw error("LIMIT", path, "Numeric token is too long");
                BigDecimal number = new BigDecimal(value);
                if (!Double.isFinite(number.doubleValue()) || Math.abs((long) number.scale()) > 1024) {
                    throw error("NUMBER", path, "Number is outside supported finite range");
                }
                // Preserve the token for the explicitly limited v0.7 source fingerprint profile.
                yield new JsonPrimitive(new TokenNumber(value));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw error("JSON", path, "Expected a JSON value");
        };
    }

    private static void unicode(String value, String path) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw error("UNICODE", path, "Unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) throw error("UNICODE", path, "Unpaired surrogate");
        }
    }

    static ConfigurationException error(String code, String path, String message) {
        return new ConfigurationException(code, path, message);
    }

    private static final class TokenNumber extends Number {
        private final String token;
        private TokenNumber(String token) { this.token = token; }
        @Override public int intValue() { return new BigDecimal(token).intValue(); }
        @Override public long longValue() { return new BigDecimal(token).longValue(); }
        @Override public float floatValue() { return Float.parseFloat(token); }
        @Override public double doubleValue() { return Double.parseDouble(token); }
        @Override public String toString() { return token; }
    }
}
