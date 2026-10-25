package dev.ifelse.zirion.genesis.core.wild;

import dev.ifelse.zirion.genesis.core.content.v2.FormIdentity;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;

import java.util.Objects;
import java.util.UUID;

/** Immutable creation proposal, not a durable store or a living Genesis NPC. Contains hidden genes. */
public final class WildCandidateRecord {
    /** Integration must prove these IDs survive restarts; a transient ECS index is not an entityId. */
    public record NpcIdentity(ContentId provider, UUID worldId, UUID entityId) {
        public NpcIdentity { Objects.requireNonNull(provider); Objects.requireNonNull(worldId); Objects.requireNonNull(entityId); }
    }

    private final WildGenomeGenerator.Replay replay;
    private final FormIdentity form;
    private final Genome genome;

    // Only the generator creates a record; restoration must verify replay before accepting external bytes.
    WildCandidateRecord(WildGenomeGenerator.Replay replay, FormIdentity form, Genome genome) {
        this.replay = Objects.requireNonNull(replay); this.form = Objects.requireNonNull(form); this.genome = Objects.requireNonNull(genome);
    }

    public UUID candidateId() { return replay.request().candidateId(); }
    public NpcIdentity npc() { return replay.request().npc(); }
    public ContentId founder() { return replay.request().founder(); }
    public WildGenomeGenerator.Replay replay() { return replay; }
    public FormIdentity form() { return form; }
    public Genome genome() { return genome; }
}
