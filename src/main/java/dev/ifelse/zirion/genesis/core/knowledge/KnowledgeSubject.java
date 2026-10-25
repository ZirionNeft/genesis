package dev.ifelse.zirion.genesis.core.knowledge;

import java.util.Objects;
import java.util.UUID;

/** Authoritative record identity, not an ECS index or a genotype-derived public identifier. */
public record KnowledgeSubject(Kind kind, UUID id) {
    public enum Kind { WILD_CANDIDATE, CREATURE, SPARK }
    public KnowledgeSubject { Objects.requireNonNull(kind); Objects.requireNonNull(id); }
}
