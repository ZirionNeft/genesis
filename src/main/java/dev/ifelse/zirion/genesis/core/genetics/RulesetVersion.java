package dev.ifelse.zirion.genesis.core.genetics;

import java.util.Objects;

public record RulesetVersion(ContentId id, int version) {
    public RulesetVersion {
        Objects.requireNonNull(id, "id");
        if (version < 1) throw new IllegalArgumentException("Ruleset version must be positive");
    }
}
