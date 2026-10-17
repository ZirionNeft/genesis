package dev.ifelse.zirion.genesis.core.genetics;

import java.util.Objects;
import java.util.regex.Pattern;

/** Internal content identifier; never interpreted as a filesystem path. */
public record ContentId(String namespace, String path) implements Comparable<ContentId> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    public ContentId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid namespaced content ID: " + namespace + ":" + path);
        }
    }

    public static ContentId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator < 0) throw new IllegalArgumentException("Content ID requires a namespace: " + value);
        return new ContentId(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public int compareTo(ContentId other) {
        int order = namespace.compareTo(other.namespace);
        return order != 0 ? order : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
