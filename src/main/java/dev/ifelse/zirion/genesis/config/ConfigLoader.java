package dev.ifelse.zirion.genesis.config;

import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static dev.ifelse.zirion.genesis.config.StrictJson.error;

/** Strict mapping to the closed v0.7 record schema; no missing-field defaults or mutable Gson in Core. */
public final class ConfigLoader {
    /** Consumes and closes the supplied source; absent fields never receive implicit defaults. */
    public ServerConfig load(InputStream source, String name) {
        return decode(new JsonNode(StrictJson.read(source, name), name + "#"));
    }

    ServerConfig decode(JsonNode source) { return (ServerConfig) decodeRecord(ServerConfig.class, source); }

    private Object decodeValue(Type type, JsonNode node) {
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == List.class) {
            var result = new ArrayList<>();
            for (JsonNode item : node.array()) result.add(decodeValue(parameterized.getActualTypeArguments()[0], item));
            return List.copyOf(result);
        }
        if (!(type instanceof Class<?> raw)) throw new IllegalStateException("Unsupported config type");
        if (raw == String.class) return node.string();
        if (raw == int.class || raw == Integer.class) return node.integer();
        if (raw == boolean.class) return node.bool();
        if (raw == BigDecimal.class) return node.number(false);
        if (raw == ContentId.class) return node.id();
        if (raw.isEnum()) {
            String name = node.string().toUpperCase(Locale.ROOT);
            for (Object candidate : raw.getEnumConstants()) if (((Enum<?>) candidate).name().equals(name)) return candidate;
            throw error("ENUM", node.path(), "Unsupported enum value");
        }
        if (raw.isRecord()) return decodeRecord(raw, node);
        throw new IllegalStateException("Unsupported config record component");
    }

    private Object decodeRecord(Class<?> type, JsonNode node) {
        var components = type.getRecordComponents();
        var expected = new HashSet<String>();
        var signature = new Class<?>[components.length];
        var arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            var component = components[i];
            String name = type == ServerConfig.Storage.class && component.getName().equals("nativeStorage")
                    ? "native" : component.getName();
            expected.add(name);
            signature[i] = component.getType();
            JsonNode field = node.field(name);
            boolean nullable = type == ServerConfig.CompanionLifecycle.class && name.equals("maxRevives");
            arguments[i] = nullable && field.value().isJsonNull() ? null : decodeValue(component.getGenericType(), field);
        }
        node.keys(expected, "UNKNOWN_FIELD");
        try {
            return type.getConstructor(signature).newInstance(arguments);
        } catch (InvocationTargetException exception) {
            // Only developer-owned record validators reach this branch; never echo input values.
            throw error("CONFIG_INVARIANT", node.path(), "Settings violate " + type.getSimpleName() + " invariants");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot construct config record " + type.getSimpleName(), exception);
        }
    }
}
