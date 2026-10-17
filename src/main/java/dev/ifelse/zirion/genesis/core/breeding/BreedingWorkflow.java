package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.content.GeneticsCatalog;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.lineage.FrailtyRandom;
import dev.ifelse.zirion.genesis.core.lineage.InbreedingPolicy;
import dev.ifelse.zirion.genesis.core.lineage.InbreedingRules;
import dev.ifelse.zirion.genesis.core.lineage.Pedigree;
import dev.ifelse.zirion.genesis.core.lineage.SeededFrailtyRandom;
import dev.ifelse.zirion.genesis.core.lineage.SpeciesAncestry;
import dev.ifelse.zirion.genesis.core.mutation.MutationPolicy;
import dev.ifelse.zirion.genesis.core.mutation.MutationRandom;
import dev.ifelse.zirion.genesis.core.mutation.MutationRules;
import dev.ifelse.zirion.genesis.core.mutation.SeededMutationRandom;

import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Pure genetic calculation for one attempt, using trusted immutable snapshots.
 * Not a Spark transaction: controller/permissions/resources, visual readiness and durable idempotency
 * must be checked by the game workflow before committing its result.
 */
public final class BreedingWorkflow {
    public static final int ALGORITHM_VERSION = 1;

    /** Extension bytes are retained even though extension inheritance is not supported by this workflow. */
    public record Parent(UUID id, BreedingParent genetics, SpeciesAncestry ancestry, List<ExtensionPayload> extensions) {
        public Parent {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(genetics, "genetics");
            Objects.requireNonNull(ancestry, "ancestry");
            extensions = List.copyOf(extensions);
        }
    }

    public record Conditions(double readinessA, double readinessB, double environment, double harmony) {
        public Conditions {
            BreedingChance.calculate(1, readinessA, readinessB, environment, harmony);
        }
    }

    public record Request(Parent parentA, Parent parentB, Pedigree pedigree, int pedigreeDepth,
                          Conditions conditions, GenerationSeed seed) {
        public Request {
            Objects.requireNonNull(parentA, "parentA");
            Objects.requireNonNull(parentB, "parentB");
            Objects.requireNonNull(pedigree, "pedigree");
            Objects.requireNonNull(conditions, "conditions");
            Objects.requireNonNull(seed, "seed");
            if (pedigreeDepth < 0) throw new IllegalArgumentException("Pedigree depth must be nonnegative");
        }
    }

    public record Policy(CompatibilityPolicy compatibility, InbreedingPolicy inbreeding, MutationPolicy mutation) {
        public Policy {
            Objects.requireNonNull(compatibility, "compatibility");
            Objects.requireNonNull(inbreeding, "inbreeding");
            Objects.requireNonNull(mutation, "mutation");
        }
    }

    public enum Reason { SAME_PARENT, INCOMPATIBLE_PARENTS, UNSUPPORTED_RULESET, UNSUPPORTED_EXTENSIONS,
        KINSHIP_REJECTED, KINSHIP_INDETERMINATE }
    public sealed interface Result permits Rejected, Failed, Conceived {}
    /** Rejected before any random draw. No attempt should be spent on this result. */
    public record Rejected(Reason reason) implements Result {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }
    /** One attempt draw, no offspring or child random streams. Resource policy belongs to the adapter. */
    public record Failed(double successProbability, double draw) implements Result {}
    /** Genetic candidate only. Unsupported visuals must never cause a retry or replacement genome. */
    public record Conceived(double successProbability, double draw, Genome inherited, MutationRules.Result mutation,
                            SpeciesAncestry ancestry, Pedigree.Estimate kinship,
                            InbreedingRules.FrailtyOutcome frailty, long cosmeticSeed) implements Result {
        public Genome genome() { return mutation.genome(); }
    }

    private final GeneticsCatalog catalog;
    private final MutationRules mutations;
    private final Set<ExtensionPayload.Key> requiredExtensions;

    /** Required dependencies come from the trusted content profile, never from caller-controlled payload flags. */
    public BreedingWorkflow(GeneticsCatalog catalog, MutationRules mutations, Set<ExtensionPayload.Key> requiredExtensions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.requiredExtensions = Set.copyOf(requiredExtensions);
        var genetics = catalog.rulesets().get(mutations.geneticsReference().id());
        if (genetics == null || !genetics.reference().equals(mutations.geneticsReference())) {
            throw new IllegalArgumentException("Mutation rules must reference this frozen catalog");
        }
    }

    public Result calculate(Request request, Policy policy) {
        Objects.requireNonNull(request, "request");
        return calculate(request, policy, new SeededStreams(request.seed()));
    }

    // Package-private seam verifies gate ordering and draw counts without exposing a second replay contract.
    interface Streams {
        double attempt();
        AlleleRandom inheritance();
        MutationRandom mutation();
        FrailtyRandom frailty();
        long cosmetic();
    }

    Result calculate(Request request, Policy policy, Streams streams) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(streams, "streams");
        Parent a = request.parentA();
        Parent b = request.parentB();
        if (a.id().equals(b.id())) return new Rejected(Reason.SAME_PARENT);
        var compatibility = BreedingCompatibility.evaluate(a.genetics(), b.genetics(), catalog, policy.compatibility());
        if (!(compatibility instanceof BreedingCompatibility.Allowed allowed)) return new Rejected(Reason.INCOMPATIBLE_PARENTS);
        if (!a.genetics().genome().ruleset().equals(mutations.geneticsReference())) return new Rejected(Reason.UNSUPPORTED_RULESET);
        // No extension inheritance/merge contract exists yet, even for recognised cosmetic data.
        // Refuse the operation instead of dropping payloads, accepting missing dependencies, or guessing inheritance.
        if (!requiredExtensions.isEmpty() || !a.extensions().isEmpty() || !b.extensions().isEmpty()) {
            return new Rejected(Reason.UNSUPPORTED_EXTENSIONS);
        }
        var kinship = request.pedigree().kinship(a.id(), b.id(), request.pedigreeDepth());
        var assessment = InbreedingRules.assess(kinship, policy.inbreeding());
        if (assessment instanceof InbreedingRules.Rejected) return new Rejected(Reason.KINSHIP_REJECTED);
        if (!(assessment instanceof InbreedingRules.Allowed accepted)) return new Rejected(Reason.KINSHIP_INDETERMINATE);
        Conditions conditions = request.conditions();
        double chance = BreedingChance.calculate(allowed.baseChance(), conditions.readinessA(), conditions.readinessB(),
                conditions.environment(), conditions.harmony());
        double draw = streams.attempt(); // Always exactly one draw, including chance=0.
        if (!Double.isFinite(draw) || draw < 0 || draw >= 1) throw new IllegalArgumentException("Attempt RNG value must be in [0,1)");
        if (draw >= chance) return new Failed(chance, draw);

        var genetics = catalog.rulesets().get(mutations.geneticsReference().id());
        Genome inherited = MendelianInheritance.inherit(a.genetics().genome(), b.genetics().genome(), genetics, streams.inheritance());
        MutationRules.Result mutation = mutations.apply(inherited, policy.mutation(), streams.mutation());
        var frailty = InbreedingRules.roll(accepted, streams.frailty());
        return new Conceived(chance, draw, inherited, mutation, SpeciesAncestry.childOf(a.ancestry(), b.ancestry()),
                kinship, frailty, streams.cosmetic());
    }

    private record SeededStreams(GenerationSeed seed) implements Streams {
        public double attempt() { return new Random(seed.forStream(GenerationSeed.Stream.ATTEMPT)).nextDouble(); }
        public AlleleRandom inheritance() { return new SeededAlleleRandom(seed.forStream(GenerationSeed.Stream.INHERITANCE)); }
        public MutationRandom mutation() { return new SeededMutationRandom(seed.forStream(GenerationSeed.Stream.MUTATION)); }
        public FrailtyRandom frailty() { return new SeededFrailtyRandom(seed.forStream(GenerationSeed.Stream.FRAILTY)); }
        public long cosmetic() { return seed.forStream(GenerationSeed.Stream.COSMETIC); }
    }
}
