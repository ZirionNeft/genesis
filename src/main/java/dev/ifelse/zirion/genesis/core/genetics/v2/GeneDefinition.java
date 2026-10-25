package dev.ifelse.zirion.genesis.core.genetics.v2;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable variants scoped to one gene; e.g. genesis:normal may occur at several genes. */
public record GeneDefinition(ContentId id, Set<ContentId> variants, Kind kind) {
    public enum Kind { TRAIT, HERITAGE }

    public GeneDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(variants, "variants");
        Objects.requireNonNull(kind, "kind");
        var copy = new TreeSet<ContentId>();
        variants.forEach(variant -> copy.add(Objects.requireNonNull(variant, "variant")));
        if (copy.isEmpty()) throw new IllegalArgumentException("Gene requires at least one variant: " + id);
        variants = Collections.unmodifiableSortedSet(copy);
    }

    public boolean mutationAllowed() {
        return kind == Kind.TRAIT;
    }
}
