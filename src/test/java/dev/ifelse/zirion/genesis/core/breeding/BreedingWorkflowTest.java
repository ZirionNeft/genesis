package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.content.CanidStarterContent;
import dev.ifelse.zirion.genesis.core.content.GeneticsCatalog;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.lineage.*;
import dev.ifelse.zirion.genesis.core.mutation.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.breeding.BreedingWorkflow.*;
import static dev.ifelse.zirion.genesis.core.content.CanidStarterContent.*;
import static org.junit.jupiter.api.Assertions.*;

class BreedingWorkflowTest {
    private static final GeneticsCatalog CATALOG = CanidStarterContent.create();
    private static final UUID A = new UUID(0, 3);
    private static final UUID B = new UUID(0, 4);
    private static final Conditions READY = new Conditions(1, 1, 1, 1);
    private static final Policy DEFAULTS = new Policy(CompatibilityPolicy.gddDefaults(), InbreedingPolicy.softDefaults(), MutationPolicy.gddDefaults());

    private static Genome heterozygote() {
        var loci = new EnumMap<Locus, AllelePair>(Locus.class);
        var rules = CATALOG.rulesets().get(RULESET.id());
        for (Locus locus : Locus.values()) {
            var alleles = rules.alleles().values().stream().filter(allele -> allele.locus() == locus)
                    .map(allele -> allele.id()).sorted().toList();
            loci.put(locus, new AllelePair(alleles.getFirst(), alleles.getLast()));
        }
        return new Genome(RULESET, loci);
    }

    private static Parent parent(UUID id, ContentId ancestry) {
        return new Parent(id, BreedingParent.hybrid(FAMILY, RIG, heterozygote()), SpeciesAncestry.original(ancestry), List.of());
    }

    private static Request request(long seed) {
        return new Request(parent(A, WOLF), parent(B, FOX),
                new Pedigree(List.of(PedigreeEntry.founder(A), PedigreeEntry.founder(B))),
                Pedigree.DEFAULT_GENERATIONS, READY, new GenerationSeed(seed));
    }

    private static Request withParents(Request source, Parent a, Parent b) {
        return new Request(a, b, source.pedigree(), source.pedigreeDepth(), source.conditions(), source.seed());
    }

    private static Request withConditions(Request source, Conditions conditions) {
        return new Request(source.parentA(), source.parentB(), source.pedigree(), source.pedigreeDepth(), conditions, source.seed());
    }

    private static Request siblings(long seed) {
        var source = request(seed);
        UUID p = new UUID(0, 1);
        UUID q = new UUID(0, 2);
        return new Request(source.parentA(), source.parentB(), new Pedigree(List.of(PedigreeEntry.founder(p),
                PedigreeEntry.founder(q), PedigreeEntry.child(A, p, q), PedigreeEntry.child(B, p, q))),
                4, READY, source.seed());
    }

    /** Synthetic mutation to an existing draft allele. It does not add final mutation content to the game. */
    private static MutationRules mutations(boolean includeTarget) {
        var allow = new EnumMap<MutationCategory, Set<Locus>>(MutationCategory.class);
        for (MutationCategory category : MutationCategory.values()) allow.put(category, Set.of());
        allow.put(MutationCategory.COSMETIC, Set.of(Locus.PIGMENT));
        var pair = heterozygote().loci().get(Locus.PIGMENT);
        var definition = new MutationDefinition(ContentId.parse("fixture:pigment"), MutationCategory.COSMETIC,
                Locus.PIGMENT, pair.first(), MutationDefinition.Impact.COSMETIC,
                Set.of(pair, new AllelePair(pair.second(), pair.second())));
        return new MutationRules(new RulesetVersion(ContentId.parse("fixture:mutations"), 1),
                CATALOG.rulesets().get(RULESET.id()), allow, includeTarget ? List.of(definition) : List.of());
    }

    private static BreedingWorkflow workflow() { return new BreedingWorkflow(CATALOG, mutations(true), Set.of()); }

    private static MutationPolicy only(MutationCategory category, double chance) {
        var chances = new EnumMap<MutationCategory, Double>(MutationCategory.class);
        for (var value : MutationCategory.values()) chances.put(value, value == category ? chance : 0.0);
        return new MutationPolicy(chances, MutationPolicy.gddDefaults().impactChances(), MutationPolicy.UnavailablePolicy.SKIP);
    }

    /** Any access to a child stream is an immediate failure unless explicitly enabled after success. */
    private static final class Traced implements Streams {
        private final List<String> calls = new ArrayList<>();
        private final double draw;
        private final boolean allowChildren;
        private int alleles;
        private int mutationUnits;
        private int mutationIndices;
        private int frailtyUnits;

        private Traced(double draw, boolean allowChildren) { this.draw = draw; this.allowChildren = allowChildren; }
        public double attempt() { calls.add("attempt"); return draw; }
        private void child(String name) { assertTrue(allowChildren, "Child stream touched before success: " + name); calls.add(name); }
        public AlleleRandom inheritance() { child("inheritance"); return () -> { alleles++; return true; }; }
        public MutationRandom mutation() {
            child("mutation");
            return new MutationRandom() {
                public double nextUnit() { mutationUnits++; return 0; }
                public int nextIndex(int bound) { mutationIndices++; return 0; }
            };
        }
        public FrailtyRandom frailty() { child("frailty"); return () -> { frailtyUnits++; return 0; }; }
        public long cosmetic() { child("cosmetic"); return 123; }
    }

    @Test
    void independentSeed42VectorRunsTheWholeSuccessfulCalculationAndReplaysExactly() {
        var input = request(42);
        var workflow = workflow();
        var conceived = assertInstanceOf(Conceived.class, workflow.calculate(input, DEFAULTS));
        assertEquals(0.7, conceived.successProbability());
        assertEquals(0.5402573282344997, conceived.draw());
        String bits = "000101111110111000100010"; // Python SHA-256 + independent Java LCG reference.
        var expected = new EnumMap<Locus, AllelePair>(Locus.class);
        for (Locus locus : Locus.values()) {
            var pair = heterozygote().loci().get(locus);
            expected.put(locus, new AllelePair(bits.charAt(2 * locus.ordinal()) == '1' ? pair.second() : pair.first(),
                    bits.charAt(2 * locus.ordinal() + 1) == '1' ? pair.second() : pair.first()));
        }
        assertEquals(expected, conceived.inherited().loci());
        assertInstanceOf(MutationRules.Unchanged.class, conceived.mutation());
        assertEquals(conceived.inherited(), conceived.genome());
        assertEquals(3213433986526642893L, conceived.cosmeticSeed());
        assertEquals(new SpeciesAncestry(Map.of(WOLF, new BigDecimal("0.5"), FOX, new BigDecimal("0.5")), BigDecimal.ZERO), conceived.ancestry());
        assertFalse(conceived.frailty().affected());
        assertEquals(conceived, workflow.calculate(input, DEFAULTS));
        assertInstanceOf(Failed.class, workflow.calculate(request(Long.MIN_VALUE), DEFAULTS));
        assertEquals(conceived, workflow.calculate(input, DEFAULTS)); // Another calculation cannot advance replay streams.
        assertEquals(conceived, workflow.calculate(new Request(input.parentA(), input.parentB(), input.pedigree(), 4,
                input.conditions(), GenerationSeed.decode(input.seed().encode())), DEFAULTS));
        assertEquals(heterozygote(), input.parentA().genetics().genome());
    }

    @Test
    void exactFailureBoundaryAndZeroChanceConsumeOnlyOneAttemptDraw() {
        var workflow = workflow();
        for (double draw : new double[]{0.7, Math.nextDown(1.0)}) {
            var streams = new Traced(draw, false);
            assertEquals(new Failed(0.7, draw), workflow.calculate(request(42), DEFAULTS, streams));
            assertEquals(List.of("attempt"), streams.calls);
        }
        var streams = new Traced(0, false);
        assertEquals(new Failed(0, 0), workflow.calculate(withConditions(request(42), new Conditions(0, 1, 1, 1)), DEFAULTS, streams));
        assertEquals(List.of("attempt"), streams.calls);
        assertInstanceOf(Failed.class, workflow.calculate(request(Long.MIN_VALUE), DEFAULTS));
    }

    @Test
    void successCallsInheritanceThenMutationExactlyOnceAndKeepsFrailtySeparate() {
        var streams = new Traced(Math.nextDown(0.7), true);
        var policy = new Policy(DEFAULTS.compatibility(), DEFAULTS.inbreeding(), only(MutationCategory.COSMETIC, 1));
        var result = assertInstanceOf(Conceived.class, workflow().calculate(request(42), policy, streams));
        assertEquals(List.of("attempt", "inheritance", "mutation", "frailty", "cosmetic"), streams.calls);
        assertEquals(24, streams.alleles);
        assertEquals(1, streams.mutationUnits);
        assertEquals(3, streams.mutationIndices);
        assertEquals(0, streams.frailtyUnits); // Unrelated reference founders have zero risk.
        var change = assertInstanceOf(MutationRules.Mutated.class, result.mutation()).change();
        assertEquals(Locus.PIGMENT, change.locus());
        int changedCopies = 0;
        for (Locus locus : Locus.values()) {
            var before = result.inherited().loci().get(locus);
            var after = result.genome().loci().get(locus);
            if (!before.equals(after)) {
                changedCopies++;
                assertEquals(Locus.PIGMENT, locus);
                assertEquals(new AllelePair(change.newAllele(), before.second()), after);
            }
        }
        assertEquals(1, changedCopies);
    }

    @Test
    void sameIdentityInvalidGenomeAndIncompatibleFamilyRejectWithoutAnyDraws() {
        var source = request(42);
        var wrongFamily = new Parent(B, BreedingParent.hybrid(ContentId.parse("missing:family"), RIG, heterozygote()), source.parentB().ancestry(), List.of());
        var wrongRig = new Parent(B, BreedingParent.hybrid(FAMILY, ContentId.parse("missing:rig"), heterozygote()), source.parentB().ancestry(), List.of());
        var invalid = new Genome(new RulesetVersion(RULESET.id(), 2), heterozygote().loci());
        var wrongVersion = new Parent(B, BreedingParent.hybrid(FAMILY, RIG, invalid), source.parentB().ancestry(), List.of());
        var streams = new Traced(0, false);
        assertEquals(new Rejected(Reason.SAME_PARENT), workflow().calculate(withParents(source, source.parentA(), source.parentA()), DEFAULTS, streams));
        for (Parent bad : List.of(wrongFamily, wrongRig, wrongVersion)) {
            assertEquals(new Rejected(Reason.INCOMPATIBLE_PARENTS), workflow().calculate(withParents(source, source.parentA(), bad), DEFAULTS, streams));
        }
        assertTrue(streams.calls.isEmpty());
    }

    @Test
    void opaqueDataAndTrustedRequiredDependenciesRejectWithoutDeletionOrRng() {
        var key = new ExtensionPayload.Key(new RulesetVersion(ContentId.parse("missing:genes"), 1), 1);
        var streams = new Traced(0, false);
        var source = request(42);
        for (var kind : ExtensionPayload.Kind.values()) {
            var bytes = new ImmutableBytes(new byte[]{0, (byte) 255, 42});
            var data = new ExtensionPayload(key, kind, bytes);
            var copy = new ArrayList<>(List.of(data));
            var parent = new Parent(A, source.parentA().genetics(), source.parentA().ancestry(), copy);
            copy.clear();
            var input = withParents(source, parent, source.parentB());
            assertEquals(new Rejected(Reason.UNSUPPORTED_EXTENSIONS), workflow().calculate(input, DEFAULTS, streams));
            assertEquals(List.of(data), input.parentA().extensions());
            assertArrayEquals(new byte[]{0, (byte) 255, 42}, input.parentA().extensions().getFirst().payload().copy());
            assertThrows(UnsupportedOperationException.class, () -> input.parentA().extensions().clear());
            assertEquals(new Rejected(Reason.UNSUPPORTED_EXTENSIONS), workflow().calculate(withParents(source, source.parentB(), parent), DEFAULTS, streams));
        }
        // Removing all payloads cannot bypass a dependency declared by the trusted content profile.
        var workflow = new BreedingWorkflow(CATALOG, mutations(true), Set.of(key));
        assertEquals(new Rejected(Reason.UNSUPPORTED_EXTENSIONS), workflow.calculate(source, DEFAULTS, streams));
        assertTrue(streams.calls.isEmpty());
    }

    @Test
    void relatedAndUnknownKinshipAreRejectedBeforeSuccessSampling() {
        var disallow = new InbreedingPolicy(InbreedingPolicy.Mode.STRICT, DEFAULTS.inbreeding().frailty(),
                new InbreedingPolicy.Restrictions(false, 0));
        var policy = new Policy(DEFAULTS.compatibility(), disallow, DEFAULTS.mutation());
        var streams = new Traced(0, false);
        assertEquals(new Rejected(Reason.KINSHIP_REJECTED), workflow().calculate(siblings(42), policy, streams));
        var source = request(42);
        var unknown = new Request(source.parentA(), source.parentB(), new Pedigree(List.of()), 4, READY, source.seed());
        assertEquals(new Rejected(Reason.KINSHIP_INDETERMINATE), workflow().calculate(unknown, DEFAULTS, streams));
        assertTrue(streams.calls.isEmpty());
    }

    @Test
    void settingsCanChangeSuccessAndFrailtyWithoutChangingInheritanceOrCosmetics() {
        var workflow = workflow();
        var input = siblings(42);
        var base = assertInstanceOf(Conceived.class, workflow.calculate(input, DEFAULTS));
        var certainFrailty = new InbreedingPolicy(InbreedingPolicy.Mode.SOFT,
                new InbreedingPolicy.FrailtySettings(true, 0, 4, 1, 0.25), new InbreedingPolicy.Restrictions(true, 0.5));
        var changed = assertInstanceOf(Conceived.class, workflow.calculate(withConditions(input, new Conditions(1, 1, 10, 10)),
                new Policy(DEFAULTS.compatibility(), certainFrailty, only(MutationCategory.COSMETIC, 1))));
        assertEquals(0.95, changed.successProbability());
        assertTrue(changed.frailty().affected());
        assertEquals(0.75, changed.frailty().healthMultiplier());
        assertEquals(base.inherited(), changed.inherited());
        assertEquals(base.cosmeticSeed(), changed.cosmeticSeed());
        assertEquals(base.kinship(), changed.kinship());
        assertFalse(base.frailty().affected());
        assertEquals(base.draw(), changed.draw());
    }

    @Test
    void unavailableMutationSkipsOnceWithoutRerollingOrChangingTheInheritedGenome() {
        var streams = new Traced(0, true);
        var policy = new Policy(DEFAULTS.compatibility(), DEFAULTS.inbreeding(), only(MutationCategory.COSMETIC, 1));
        var workflow = new BreedingWorkflow(CATALOG, mutations(false), Set.of());
        var result = assertInstanceOf(Conceived.class, workflow.calculate(request(42), policy, streams));
        assertEquals(MutationRules.SkipReason.CATEGORY_UNAVAILABLE, assertInstanceOf(MutationRules.Skipped.class, result.mutation()).reason());
        assertEquals(result.inherited(), result.genome());
        assertEquals(1, streams.mutationUnits);
        assertEquals(0, streams.mutationIndices);
        assertEquals(24, streams.alleles);
    }

    @Test
    void extraMutationDrawsCannotAdvanceTheFrailtyStream() {
        var input = siblings(42);
        var risk = new InbreedingPolicy(InbreedingPolicy.Mode.SOFT,
                new InbreedingPolicy.FrailtySettings(true, 0, 3.6, 0.9, 0.08), new InbreedingPolicy.Restrictions(true, 0.5));
        var workflow = workflow();
        var unchanged = assertInstanceOf(Conceived.class, workflow.calculate(input,
                new Policy(DEFAULTS.compatibility(), risk, only(MutationCategory.COSMETIC, 0))));
        var mutated = assertInstanceOf(Conceived.class, workflow.calculate(input,
                new Policy(DEFAULTS.compatibility(), risk, only(MutationCategory.COSMETIC, 1))));
        assertInstanceOf(MutationRules.Unchanged.class, unchanged.mutation());
        assertInstanceOf(MutationRules.Mutated.class, mutated.mutation());
        assertTrue(unchanged.frailty().affected()); // Independent Python draw 0.8660122488623799 < 0.9.
        assertEquals(unchanged.frailty(), mutated.frailty());
        assertEquals(unchanged.inherited(), mutated.inherited());
        assertEquals(unchanged.cosmeticSeed(), mutated.cosmeticSeed());
    }

    @Test
    void invalidAttemptRngFailsBeforeAnyChildStream() {
        for (double value : new double[]{-0.1, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var streams = new Traced(value, false);
            assertThrows(IllegalArgumentException.class, () -> workflow().calculate(request(42), DEFAULTS, streams));
            assertEquals(List.of("attempt"), streams.calls);
        }
    }

    @Test
    void invalidInputsAndMismatchedFrozenMutationDefinitionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Conditions(1.1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Conditions(1, 1, Double.NaN, 1));
        var source = request(42);
        assertThrows(IllegalArgumentException.class, () -> new Request(source.parentA(), source.parentB(), source.pedigree(), -1, READY, source.seed()));
        var emptyCatalog = GeneticsCatalog.builder(ContentId.parse("fixture:empty"), 1).freeze();
        assertThrows(IllegalArgumentException.class, () -> new BreedingWorkflow(emptyCatalog, mutations(true), Set.of()));
    }

    @Test
    void oneHundredThousandAttemptsPreserveConditionalMendelianAndFrailtyFrequencies() {
        var workflow = workflow();
        var source = siblings(0);
        var policy = new Policy(DEFAULTS.compatibility(), new InbreedingPolicy(InbreedingPolicy.Mode.SOFT,
                new InbreedingPolicy.FrailtySettings(true, 0, 1, 1, 0.08), new InbreedingPolicy.Restrictions(true, 0.5)),
                DEFAULTS.mutation());
        int attempts = 100_000;
        int successes = 0;
        int affected = 0;
        int[] frame = new int[3];
        var pair = heterozygote().loci().get(Locus.FRAME);
        for (int seed = 0; seed < attempts; seed++) {
            var input = new Request(source.parentA(), source.parentB(), source.pedigree(), 4, READY, new GenerationSeed(seed));
            var result = workflow.calculate(input, policy);
            if (result instanceof Conceived conceived) {
                successes++;
                if (conceived.frailty().affected()) affected++;
                var inherited = conceived.inherited().loci().get(Locus.FRAME);
                int copies = (inherited.first().equals(pair.second()) ? 1 : 0) + (inherited.second().equals(pair.second()) ? 1 : 0);
                frame[copies]++;
            } else assertInstanceOf(Failed.class, result);
        }
        frequency(successes, attempts, 0.7);
        frequency(frame[0], successes, 0.25);
        frequency(frame[1], successes, 0.5);
        frequency(frame[2], successes, 0.25);
        frequency(affected, successes, 0.25);
    }

    private static void frequency(int actual, int count, double probability) {
        assertEquals(count * probability, actual, 6 * Math.sqrt(count * probability * (1 - probability)),
                "Fixed-seed workflow frequency differs by more than six binomial sigmas");
    }
}
