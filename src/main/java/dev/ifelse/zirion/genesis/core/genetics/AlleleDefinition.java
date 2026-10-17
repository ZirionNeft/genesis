package dev.ifelse.zirion.genesis.core.genetics;

import java.util.Objects;

public record AlleleDefinition(ContentId id, Locus locus) {
    public AlleleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(locus, "locus");
    }
}
