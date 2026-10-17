package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.util.Objects;
import java.util.Set;

public record FamilyDefinition(ContentId id, int version, Set<ContentId> supportedRigs, RulesetVersion ruleset) {
    public FamilyDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ruleset, "ruleset");
        supportedRigs = Set.copyOf(supportedRigs);
        if (version < 1 || supportedRigs.isEmpty()) {
            throw new IllegalArgumentException("Family needs a positive version and at least one rig");
        }
    }
}
