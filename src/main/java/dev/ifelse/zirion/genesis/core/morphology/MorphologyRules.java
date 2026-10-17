package dev.ifelse.zirion.genesis.core.morphology;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact semantic signatures -> registered appearance plans. No genotype substitution or asset guessing. */
public final class MorphologyRules {
    public record Signature(Map<Locus, ContentId> traits) {
        public Signature { traits = Map.copyOf(traits); }
    }

    /** Reference to an adapter-owned modular or whole model plan, including animations and collision. */
    public record Appearance(ContentId id, int version, ContentId rigId) {
        public Appearance {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(rigId, "rigId");
            if (version < 1) throw new IllegalArgumentException("Appearance version must be positive");
        }
    }

    public sealed interface Result permits Resolved, Unsupported {}
    public record Resolved(Signature signature, Appearance appearance) implements Result {}
    public record Unsupported(Signature signature) implements Result {}
    public record Coverage(BigInteger required, int registered) {
        public boolean complete() { return required.equals(BigInteger.valueOf(registered)); }
    }

    private final RulesetVersion reference;
    private final GeneticsRuleset genetics;
    private final ContentId rigId;
    private final Set<Locus> visualLoci;
    private final Map<Signature, Appearance> appearances;
    private final Coverage coverage;

    /** The caller explicitly declares which loci affect this visual layer; overlays may add more. */
    public MorphologyRules(RulesetVersion reference, GeneticsRuleset genetics, ContentId rigId,
                           Set<Locus> visualLoci, Map<Signature, Appearance> appearances) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.genetics = Objects.requireNonNull(genetics, "genetics");
        this.rigId = Objects.requireNonNull(rigId, "rigId");
        this.visualLoci = Set.copyOf(visualLoci);
        if (this.visualLoci.isEmpty()) throw new IllegalArgumentException("Visual layer needs at least one locus");
        this.appearances = Map.copyOf(appearances);
        this.appearances.forEach((signature, appearance) -> {
            if (!signature.traits().keySet().equals(this.visualLoci)) {
                throw new IllegalArgumentException("Appearance signature does not match declared visual loci");
            }
            signature.traits().forEach((locus, expression) -> {
                if (!genetics.expressionOptions(locus).contains(expression)) {
                    throw new IllegalArgumentException("Unknown visual expression at " + locus + ": " + expression);
                }
            });
            if (!rigId.equals(appearance.rigId())) throw new IllegalArgumentException("Appearance uses another rig");
        });
        BigInteger required = BigInteger.ONE;
        for (Locus locus : this.visualLoci) {
            required = required.multiply(BigInteger.valueOf(genetics.expressionOptions(locus).size()));
        }
        coverage = new Coverage(required, this.appearances.size());
    }

    public RulesetVersion reference() { return reference; }

    public RulesetVersion geneticsReference() { return genetics.reference(); }

    public ContentId rigId() { return rigId; }

    /** References to plans only; the asset loader must verify their actual content. */
    public Set<Appearance> registeredAppearances() { return Set.copyOf(appearances.values()); }

    /** Counts reachable expression combinations, not genotype pairs; does not certify actual assets. */
    public Coverage coverage() { return coverage; }

    public Result resolve(Genome genome) {
        Map<Locus, ContentId> expressions = genetics.resolveExpressions(genome);
        EnumMap<Locus, ContentId> traits = new EnumMap<>(Locus.class);
        for (Locus locus : Locus.values()) if (visualLoci.contains(locus)) traits.put(locus, expressions.get(locus));
        Signature signature = new Signature(traits);
        Appearance appearance = appearances.get(signature);
        return appearance == null ? new Unsupported(signature) : new Resolved(signature, appearance);
    }
}
