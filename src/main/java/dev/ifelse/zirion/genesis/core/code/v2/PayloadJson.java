package dev.ifelse.zirion.genesis.core.code.v2;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JCS for the closed GEN1 value domain: strings, int32, objects and arrays. Not a general number serializer. */
final class PayloadJson {
    private static final int MAX_DEPTH = 16; // The closed schema requires at most five containers.
    private PayloadJson() {}

    static byte[] write(Object value, int maxBytes) {
        var output = new StringBuilder(); write(value, output, maxBytes, 0);
        byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw invalid("JSON payload exceeds limit");
        return bytes;
    }

    private static void write(Object value, StringBuilder out, int limit, int depth) {
        if (depth > MAX_DEPTH) throw invalid("JSON nesting exceeds limit");
        switch (value) {
            case String text -> quote(text, out, limit);
            case Integer integer -> out.append(integer);
            case Map<?, ?> object -> {
                var sorted = new TreeMap<String, Object>(); // Java String order is UTF-16 code-unit order (JCS).
                object.forEach((key, item) -> {
                    if (!(key instanceof String text)) throw invalid("JSON key must be a string");
                    sorted.put(text, item);
                });
                out.append('{'); boolean first = true;
                for (var entry : sorted.entrySet()) {
                    if (!first) out.append(','); first = false; quote(entry.getKey(), out, limit); out.append(':');
                    write(entry.getValue(), out, limit, depth + 1);
                }
                out.append('}');
            }
            case List<?> array -> {
                out.append('['); boolean first = true;
                for (var item : array) { if (!first) out.append(','); first = false; write(item, out, limit, depth + 1); }
                out.append(']');
            }
            case null, default -> throw invalid("Value outside the closed GEN1 JSON domain");
        }
        if (out.length() > limit) throw invalid("JSON payload exceeds limit");
    }

    private static void quote(String text, StringBuilder out, int limit) {
        if ((long) text.length() + out.length() + 2 > limit) throw invalid("JSON payload exceeds limit");
        unicode(text); out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\t' -> out.append("\\t"); case '\n' -> out.append("\\n");
                case '\f' -> out.append("\\f"); case '\r' -> out.append("\\r");
                default -> {
                    if (c < 32) {
                        out.append("\\u00"); out.append(Character.forDigit(c >>> 4, 16)); out.append(Character.forDigit(c & 15, 16));
                    } else out.append(c);
                }
            }
            if (out.length() > limit) throw invalid("JSON payload exceeds limit");
        }
        out.append('"');
    }

    static Object read(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var reader = new Reader(text); Object result = reader.value(0); reader.space();
            if (reader.position != text.length()) throw invalid("Trailing JSON data");
            return result;
        } catch (CharacterCodingException exception) { throw invalid("Invalid UTF-8"); }
    }

    private static void unicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) throw invalid("Unpaired surrogate");
            } else if (Character.isLowSurrogate(c)) throw invalid("Unpaired surrogate");
        }
    }

    private static final class Reader {
        private final String text;
        private int position;
        private Reader(String text) { this.text = text; }
        private void space() { while (position < text.length() && " \r\n\t".indexOf(text.charAt(position)) >= 0) position++; }
        private boolean take(char c) { if (position < text.length() && text.charAt(position) == c) { position++; return true; } return false; }
        private void require(char c) { if (!take(c)) throw invalid("Unexpected JSON token"); }

        private Object value(int depth) {
            if (depth > MAX_DEPTH) throw invalid("JSON nesting exceeds limit");
            space(); if (position >= text.length()) throw invalid("Truncated JSON");
            if (text.charAt(position) == '"') return string();
            if (take('{')) {
                var result = new LinkedHashMap<String, Object>(); space(); if (take('}')) return result;
                do {
                    space(); String key = string(); space(); require(':'); Object item = value(depth + 1);
                    if (result.putIfAbsent(key, item) != null) throw invalid("Duplicate JSON key");
                    space(); if (take('}')) return result; require(',');
                } while (true);
            }
            if (take('[')) {
                var result = new ArrayList<Object>(); space(); if (take(']')) return result;
                do { result.add(value(depth + 1)); space(); if (take(']')) return result; require(','); } while (true);
            }
            int start = position; take('-');
            while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
            String number = text.substring(start, position);
            if (!number.matches("-?(0|[1-9][0-9]*)")) throw invalid("Expected GEN1 int32 value");
            try { return Integer.valueOf(number); }
            catch (NumberFormatException exception) { throw invalid("Integer outside GEN1 int32 range"); }
        }

        private String string() {
            require('"'); var result = new StringBuilder();
            while (position < text.length()) {
                char c = text.charAt(position++);
                if (c == '"') { unicode(result.toString()); return result.toString(); }
                if (c < 32) throw invalid("Unescaped JSON control character");
                if (c != '\\') { result.append(c); continue; }
                if (position == text.length()) throw invalid("Truncated JSON escape");
                char escape = text.charAt(position++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b'); case 't' -> result.append('\t'); case 'n' -> result.append('\n');
                    case 'f' -> result.append('\f'); case 'r' -> result.append('\r');
                    case 'u' -> {
                        if (position + 4 > text.length()) throw invalid("Truncated Unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            char hex = text.charAt(position++); int digit = "0123456789abcdef".indexOf(Character.toLowerCase(hex));
                            if (digit < 0) throw invalid("Invalid Unicode escape"); code = code * 16 + digit;
                        }
                        result.append((char) code);
                    }
                    default -> throw invalid("Invalid JSON escape");
                }
            }
            throw invalid("Unterminated JSON string");
        }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
