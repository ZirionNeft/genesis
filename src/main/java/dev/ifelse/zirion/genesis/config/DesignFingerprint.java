package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonElement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * Limited verifier for fingerprints already supplied with the v0.7 design package.
 * ASCII object keys, ordinary decimal numeric tokens, sorted compact UTF-8 JSON.
 * This is NOT RFC8785 or a new GEN1 encoding; unsupported profiles fail explicitly.
 */
final class DesignFingerprint {
    private DesignFingerprint() {}

    static String sha256(JsonNode source) {
        var text = new StringBuilder();
        write(source, text);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static void write(JsonNode source, StringBuilder output) {
        JsonElement value = source.value();
        if (value.isJsonObject()) {
            output.append('{');
            boolean first = true;
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet())) {
                source.require(key.chars().allMatch(ch -> ch < 128), "FINGERPRINT_PROFILE", "Non-ASCII object keys need a new fingerprint contract");
                if (!first) output.append(','); first = false;
                quote(key, output); output.append(':'); write(source.field(key), output);
            }
            output.append('}');
        } else if (value.isJsonArray()) {
            output.append('[');
            boolean first = true;
            for (JsonNode item : source.array()) {
                if (!first) output.append(','); first = false; write(item, output);
            }
            output.append(']');
        } else if (value.isJsonNull()) output.append("null");
        else if (value.getAsJsonPrimitive().isBoolean()) output.append(value.getAsBoolean());
        else if (value.getAsJsonPrimitive().isString()) quote(value.getAsString(), output);
        else {
            String token = value.getAsString();
            source.require(!token.contains("e") && !token.contains("E"), "FINGERPRINT_PROFILE", "Exponent tokens need an explicit fingerprint contract");
            BigDecimal number = source.number(false);
            source.require(number.abs().compareTo(new BigDecimal("9007199254740991")) <= 0,
                    "FINGERPRINT_PROFILE", "Numeric token exceeds this source profile");
            boolean floating = token.contains(".");
            if (floating) {
                source.require(BigDecimal.valueOf(number.doubleValue()).compareTo(number) == 0,
                        "FINGERPRINT_PROFILE", "High precision numeric token needs an explicit fingerprint contract");
            }
            String decimal = number.stripTrailingZeros().toPlainString();
            if (floating && number.signum() == 0 && token.startsWith("-")) decimal = "-0";
            output.append(decimal);
            if (floating && !decimal.contains(".")) output.append(".0");
        }
    }

    private static void quote(String value, StringBuilder output) {
        output.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (ch < 32) output.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
                    else output.append(ch);
                }
            }
        }
        output.append('"');
    }
}
