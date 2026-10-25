package dev.ifelse.zirion.genesis.core.wild;

import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.wild.WildDistributionPolicy.*;

/** Pure first-observation proposal. Durable uniqueness and safe vanilla adoption belong to stages 2A/0A. */
public final class WildGenomeGenerator {
    public static final RulesetVersion ALGORITHM = new RulesetVersion(ContentId.parse("genesis:wild_genome"), 2);
    public static final ContentId ARCANA_DISTRIBUTION = ContentId.parse("genesis:wild-arcana-v1");
    public enum Affinity { ORDINARY, AFFINITY_TAGGED }

    /** Affinity is an explicit trusted integration input; this core never guesses tags from NPCs/biomes. */
    public record Request(WildCandidateRecord.NpcIdentity npc, UUID candidateId, ContentId founder, Affinity affinity, long seed) {
        public Request { Objects.requireNonNull(npc); Objects.requireNonNull(candidateId); Objects.requireNonNull(founder); Objects.requireNonNull(affinity); }
    }

    public record Replay(RulesetVersion algorithm, WildGenerationContext context, Request request) {
        public Replay { Objects.requireNonNull(algorithm); Objects.requireNonNull(context); Objects.requireNonNull(request); }
    }

    private final WildGenerationContext context;
    public WildGenomeGenerator(WildGenerationContext context) { this.context = Objects.requireNonNull(context); }

    /** Existing authoritative record wins over new seeds, affinity and policy; wrong identity/species is an error. */
    public WildCandidateRecord observe(Request request, Optional<WildCandidateRecord> existing) {
        Objects.requireNonNull(request); Objects.requireNonNull(existing);
        if (existing.isPresent()) {
            WildCandidateRecord record = existing.orElseThrow();
            if (!record.npc().equals(request.npc()) || !record.founder().equals(request.founder())) {
                throw new IllegalArgumentException("Existing wild record belongs to another NPC or species");
            }
            return record;
        }
        return generate(new Replay(ALGORITHM, context, request));
    }

    public static WildCandidateRecord replay(Replay replay) {
        Objects.requireNonNull(replay);
        if (!ALGORITHM.equals(replay.algorithm())) throw new IllegalArgumentException("Unsupported wild algorithm");
        return generate(replay);
    }

    private static WildCandidateRecord generate(Replay replay) {
        var request = replay.request(); var context = replay.context();
        var profile = context.policy().founders().get(request.founder());
        if (profile == null) throw new IllegalArgumentException("Wild founder has no approved distribution profile");
        var form = context.catalogs().hybridGraph().form(request.founder());
        var schema = context.catalogs().families().get(form.familyId());
        var genes = new TreeMap<ContentId, AllelePair>();
        FamilyGeneSchema.HERITAGE_GENES.forEach(gene -> genes.put(gene, new AllelePair(request.founder(), request.founder())));
        profile.genes().forEach((gene, distribution) -> genes.put(gene, distribution.sample(draw(request, gene.toString()))));
        BigDecimal probability = request.affinity() == Affinity.AFFINITY_TAGGED
                ? context.arcana().affinityCandidateRate() : context.arcana().ordinaryCandidateRate();
        boolean awake = new BigDecimal(draw(request, "arcana_gate")).compareTo(probability) < 0;
        genes.put(ARCANA, awake ? profile.awakenedArcana().sample(draw(request, "arcana_awake_pair")) : new AllelePair(QUIET, QUIET));
        return new WildCandidateRecord(replay, context.catalogs().hybridGraph().identity(form.id()), schema.createGenome(genes));
    }

    /** One independent LCG48 nextDouble per domain, seeded by first signed big-endian 64 bits of SHA-256. */
    static double draw(Request request, String stream) {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.write("genesis:wild_rng_v1\0".getBytes(StandardCharsets.UTF_8)); out.writeLong(request.seed());
            WildDistributionPolicy.text(out, request.founder().toString()); WildDistributionPolicy.text(out, stream);
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
            return new Random(ByteBuffer.wrap(hash).getLong()).nextDouble();
        } catch (IOException | NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
