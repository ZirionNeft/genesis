package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.ifelse.zirion.genesis.config.StrictJson.error;

record JsonNode(JsonElement value, String path) {
    JsonNode field(String name) {
        if (!object().has(name)) throw error("MISSING_FIELD", path + "/" + escape(name), "Required field is missing");
        return new JsonNode(value.getAsJsonObject().get(name), path + "/" + escape(name));
    }

    JsonObject object() {
        if (!value.isJsonObject()) throw error("TYPE", path, "Expected object");
        return value.getAsJsonObject();
    }

    List<JsonNode> array() {
        if (!value.isJsonArray()) throw error("TYPE", path, "Expected array");
        var result = new ArrayList<JsonNode>();
        for (int i = 0; i < value.getAsJsonArray().size(); i++) result.add(new JsonNode(value.getAsJsonArray().get(i), path + "/" + i));
        return result;
    }

    String string() {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isEmpty()) {
            throw error("TYPE", path, "Expected nonempty string");
        }
        return value.getAsString();
    }

    ContentId id() {
        try { return ContentId.parse(string()); }
        catch (IllegalArgumentException exception) {
            if (exception instanceof ConfigurationException failure) throw failure;
            throw error("ID", path, "Invalid namespaced ID");
        }
    }

    BigDecimal number(boolean allowString) {
        if (!value.isJsonPrimitive() || !(value.getAsJsonPrimitive().isNumber()
                || allowString && value.getAsJsonPrimitive().isString())) throw error("TYPE", path, "Expected number");
        try {
            BigDecimal result = new BigDecimal(value.getAsString());
            if (!Double.isFinite(result.doubleValue()) || Math.abs((long) result.scale()) > 1024) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) { throw error("NUMBER", path, "Expected a supported finite decimal"); }
    }

    int integer() {
        try { return number(false).intValueExact(); }
        catch (ArithmeticException exception) { throw error("TYPE", path, "Expected an exact 32-bit integer"); }
    }

    boolean bool() {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw error("TYPE", path, "Expected boolean");
        return value.getAsBoolean();
    }

    List<String> strings() {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();
        for (JsonNode item : array()) {
            String token = item.string();
            if (!seen.add(token)) throw error("DUPLICATE", item.path, "Duplicate array entry");
            result.add(token);
        }
        return result;
    }

    void keys(Set<String> expected, String code) {
        require(object().keySet().equals(expected), code, "Object field set differs from required definitions");
    }

    void require(boolean condition, String code, String message) {
        if (!condition) throw error(code, path, message);
    }

    static String escape(String value) { return value.replace("~", "~0").replace("/", "~1"); }
}
