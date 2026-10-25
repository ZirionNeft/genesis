package dev.ifelse.zirion.genesis.core.knowledge;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Server-only facts. Observing a visual slot never learns an allele pair. Never serialize this into a passport. */
public record KnowledgeSnapshot(UUID viewerId, KnowledgeSubject subject, String blueprintSha256, long revision,
                                Set<String> observedVisualSlots, Set<ContentId> analyzedGenes) {
    public static final RulesetVersion MODEL = new RulesetVersion(ContentId.parse("genesis:individual_knowledge"), 2);

    public KnowledgeSnapshot {
        Objects.requireNonNull(viewerId); Objects.requireNonNull(subject); Objects.requireNonNull(blueprintSha256);
        if (revision < 0 || !blueprintSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid knowledge revision or binding");
        observedVisualSlots = Collections.unmodifiableSortedSet(new TreeSet<>(observedVisualSlots));
        analyzedGenes = Collections.unmodifiableSortedSet(new TreeSet<>(analyzedGenes));
    }
}
