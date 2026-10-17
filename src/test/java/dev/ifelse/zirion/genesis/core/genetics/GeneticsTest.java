package dev.ifelse.zirion.genesis.core.genetics;

import dev.ifelse.zirion.genesis.core.breeding.MendelianInheritance;
import dev.ifelse.zirion.genesis.core.breeding.SeededAlleleRandom;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeneticsTest {
    private static final RulesetVersion VERSION = new RulesetVersion(ContentId.parse("fixture:baseline"), 1);

    private static ContentId allele(Locus locus, String variant) {
        return ContentId.parse("fixture:" + locus.name().toLowerCase(Locale.ROOT) + "/" + variant);
    }

    private static ContentId expression(Locus locus, String variant) {
        return ContentId.parse("fixture:expression/" + locus.name().toLowerCase(Locale.ROOT) + "/" + variant);
    }

    private static AllelePair pair(Locus locus, String a, String b) {
        return new AllelePair(allele(locus, a), allele(locus, b));
    }

    private static Map<Locus, AllelePair> pairs(String a, String b) {
        EnumMap<Locus, AllelePair> result = new EnumMap<>(Locus.class);
        for (Locus locus : Locus.values()) result.put(locus, pair(locus, a, b));
        return result;
    }

    private static Genome genome(String a, String b) {
        return new Genome(VERSION, pairs(a, b));
    }

    /** Synthetic dominant-a tables exercise the engine; these are not Genesis species/balance. */
    private static GeneticsRuleset.Builder builder(boolean includeFrameCross) {
        var builder = GeneticsRuleset.builder(VERSION);
        for (Locus locus : Locus.values()) {
            builder.registerAllele("fixture", new AlleleDefinition(allele(locus, "a"), locus));
            builder.registerAllele("fixture", new AlleleDefinition(allele(locus, "b"), locus));
            builder.registerExpression(locus, pair(locus, "a", "a"), expression(locus, "a"));
            builder.registerExpression(locus, pair(locus, "b", "b"), expression(locus, "b"));
            if (locus != Locus.FRAME || includeFrameCross) {
                builder.registerExpression(locus, pair(locus, "a", "b"), expression(locus, "a"));
            }
        }
        return builder;
    }

    @Test
    void validatesExplicitNamespacedIdsAndRulesetVersions() {
        assertEquals(new ContentId("genesis", "allele/head_fox"), ContentId.parse("genesis:allele/head_fox"));
        for (String invalid : List.of("fox", "", ":fox", "genesis:", "Genesis:fox", "genesis:FOX",
                "genesis:head:fox", "genesis:fox head", " genesis:fox")) {
            assertThrows(IllegalArgumentException.class, () -> ContentId.parse(invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class, () -> new RulesetVersion(VERSION.id(), 0));
        assertThrows(IllegalArgumentException.class, () -> new RulesetVersion(VERSION.id(), -1));
    }

    @Test
    void canonicalizesPairsWithoutLosingEitherCopy() {
        var a = allele(Locus.HEAD, "a");
        var b = allele(Locus.HEAD, "b");
        assertEquals(new AllelePair(a, b), new AllelePair(b, a));
        assertEquals(a, new AllelePair(b, a).first());
        assertEquals(b, new AllelePair(b, a).second());
        assertEquals(new AllelePair(a, a), pair(Locus.HEAD, "a", "a"));
        assertThrows(NullPointerException.class, () -> new AllelePair(a, null));
    }

    @Test
    void requiresAllTwelveLociAndMakesDefensiveOrderedSnapshots() {
        var input = pairs("b", "a");
        var genome = new Genome(VERSION, input);
        input.clear();
        assertEquals(12, genome.loci().size());
        assertEquals(List.of(Locus.values()), List.copyOf(genome.loci().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> genome.loci().clear());
        for (Locus locus : Locus.values()) {
            var missing = pairs("a", "b");
            missing.remove(locus);
            assertThrows(IllegalArgumentException.class, () -> new Genome(VERSION, missing));
        }
        var nullPair = pairs("a", "b");
        nullPair.put(Locus.HEAD, null);
        assertThrows(IllegalArgumentException.class, () -> new Genome(VERSION, nullPair));
    }

    @Test
    void rejectsNamespaceWritesDuplicateIdsAndReversedExpressionOverwrites() {
        var builder = builder(true);
        assertThrows(IllegalArgumentException.class, () -> builder.registerAllele("external",
                new AlleleDefinition(ContentId.parse("genesis:reserved"), Locus.HEAD)));
        assertThrows(IllegalArgumentException.class, () -> builder.registerAllele("fixture",
                new AlleleDefinition(allele(Locus.HEAD, "a"), Locus.FRAME)));
        assertThrows(IllegalArgumentException.class, () -> builder.registerExpression(Locus.HEAD,
                pair(Locus.HEAD, "b", "a"), expression(Locus.HEAD, "replacement")));
        // Rejected overwrites do not damage the existing definitions.
        assertEquals(expression(Locus.HEAD, "a"), builder.freeze().resolveExpressions(genome("a", "b")).get(Locus.HEAD));
    }

    @Test
    void rejectsUnregisteredAndWrongLocusExpressionReferences() {
        var builder = builder(true);
        assertThrows(IllegalArgumentException.class, () -> builder.registerExpression(Locus.HEAD,
                pair(Locus.HEAD, "a", "unknown"), expression(Locus.HEAD, "a")));
        assertThrows(IllegalArgumentException.class, () -> builder.registerExpression(Locus.HEAD,
                pair(Locus.FRAME, "a", "b"), expression(Locus.HEAD, "a")));
    }

    @Test
    void missingExpressionFailsFreezeWithoutLockingOutRepair() {
        var builder = builder(false);
        assertThrows(IllegalStateException.class, builder::freeze);
        builder.registerExpression(Locus.FRAME, pair(Locus.FRAME, "a", "b"), expression(Locus.FRAME, "a"));
        var frozen = builder.freeze();
        assertSame(frozen, builder.freeze());
        assertEquals(expression(Locus.FRAME, "a"), frozen.resolveExpressions(genome("b", "a")).get(Locus.FRAME));
        assertThrows(IllegalStateException.class, () -> GeneticsRuleset.builder(VERSION).freeze());
    }

    @Test
    void validatesAllSixPairsForThreeAllelesInsteadOfAssumingBinaryDominance() {
        var builder = builder(true);
        var c = allele(Locus.HEAD, "c");
        builder.registerAllele("fixture", new AlleleDefinition(c, Locus.HEAD));
        builder.registerExpression(Locus.HEAD, pair(Locus.HEAD, "a", "c"), expression(Locus.HEAD, "intermediate"));
        builder.registerExpression(Locus.HEAD, pair(Locus.HEAD, "b", "c"), expression(Locus.HEAD, "combined"));
        assertThrows(IllegalStateException.class, builder::freeze); // c/c still missing
        builder.registerExpression(Locus.HEAD, pair(Locus.HEAD, "c", "c"), expression(Locus.HEAD, "c"));
        var ruleset = builder.freeze();
        var loci = pairs("a", "b");
        loci.put(Locus.HEAD, pair(Locus.HEAD, "c", "b"));
        assertEquals(expression(Locus.HEAD, "combined"), ruleset.resolveExpressions(new Genome(VERSION, loci)).get(Locus.HEAD));
    }

    @Test
    void frozenDefinitionsAndResolvedSnapshotsCannotBeChanged() {
        var builder = builder(true);
        var frozen = builder.freeze();
        assertThrows(IllegalStateException.class, () -> builder.registerAllele("fixture",
                new AlleleDefinition(allele(Locus.HEAD, "c"), Locus.HEAD)));
        assertThrows(IllegalStateException.class, () -> builder.registerExpression(Locus.HEAD,
                pair(Locus.HEAD, "a", "a"), expression(Locus.HEAD, "other")));
        assertThrows(UnsupportedOperationException.class, () -> frozen.alleles().clear());
        var resolved = frozen.resolveExpressions(genome("a", "b"));
        assertThrows(UnsupportedOperationException.class, resolved::clear);
        assertEquals(genome("a", "b"), genome("b", "a"));
        for (Locus locus : Locus.values()) assertEquals(expression(locus, "a"), resolved.get(locus));
    }

    @Test
    void invalidParentsFailBeforeAnyRandomChoiceAndPreserveUnknownInput() {
        var ruleset = builder(true).freeze();
        var valid = genome("a", "b");
        var count = new AtomicInteger();
        var unknownLoci = pairs("a", "b");
        unknownLoci.put(Locus.ARCANA, pair(Locus.ARCANA, "a", "missing_extension"));
        var unknown = new Genome(VERSION, unknownLoci);
        var misplaced = pairs("a", "b");
        misplaced.put(Locus.HEAD, pair(Locus.FRAME, "a", "b"));
        var invalid = List.of(unknown, new Genome(VERSION, misplaced),
                new Genome(new RulesetVersion(VERSION.id(), 2), valid.loci()),
                new Genome(new RulesetVersion(ContentId.parse("fixture:other"), 1), valid.loci()));
        for (Genome parent : invalid) {
            assertThrows(IllegalArgumentException.class, () -> MendelianInheritance.inherit(valid, parent,
                    ruleset, () -> { count.incrementAndGet(); return false; }));
            assertThrows(IllegalArgumentException.class, () -> MendelianInheritance.inherit(parent, valid,
                    ruleset, () -> { count.incrementAndGet(); return false; }));
        }
        assertEquals(0, count.get());
        assertEquals(allele(Locus.ARCANA, "missing_extension"), unknown.loci().get(Locus.ARCANA).second());
    }

    @Test
    void homozygousParentsEachContributeOneCopyAtEveryLocus() {
        var ruleset = builder(true).freeze();
        var count = new AtomicInteger();
        var child = MendelianInheritance.inherit(genome("a", "a"), genome("b", "b"), ruleset,
                () -> count.incrementAndGet() % 2 == 0);
        assertEquals(genome("a", "b"), child);
        assertEquals(24, count.get(), "Draw schedule also includes homozygous copies");
        ruleset.validate(child);
    }

    @Test
    void injectedChoicesAreIndependentAcrossParentsAndLoci() {
        boolean[] draws = {false, false, false, true, true, false, true, true};
        AtomicInteger index = new AtomicInteger();
        var parent = genome("a", "b");
        var child = MendelianInheritance.inherit(parent, parent, builder(true).freeze(),
                () -> draws[index.getAndIncrement() % draws.length]);
        String[] expected = {"aa", "ab", "ab", "bb"};
        for (Locus locus : Locus.values()) {
            String copies = expected[locus.ordinal() % 4];
            assertEquals(pair(locus, copies.substring(0, 1), copies.substring(1)), child.loci().get(locus));
        }
        assertEquals(24, index.get());
        assertEquals(genome("a", "b"), parent);
    }

    @Test
    void analyticalDistributionMatchesMendelianCasesAndAggregatesCanonicalPairs() {
        var aa = pair(Locus.FRAME, "a", "a");
        var ab = pair(Locus.FRAME, "a", "b");
        var bb = pair(Locus.FRAME, "b", "b");
        assertEquals(Map.of(aa, 1.0), MendelianInheritance.distribution(aa, aa));
        assertEquals(Map.of(ab, 1.0), MendelianInheritance.distribution(aa, bb));
        assertEquals(Map.of(aa, 0.5, ab, 0.5), MendelianInheritance.distribution(ab, aa));
        assertEquals(Map.of(aa, 0.25, ab, 0.5, bb, 0.25), MendelianInheritance.distribution(ab, ab));
        assertEquals(MendelianInheritance.distribution(ab, aa), MendelianInheritance.distribution(aa, ab));
    }

    @Test
    void seededRandomAndOffspringMatchFixedPortableVectors() {
        assertEquals("genesis:java_random_lcg48_v1", SeededAlleleRandom.ALGORITHM.toString());
        assertEquals(1, MendelianInheritance.ALGORITHM_VERSION);
        var random = new SeededAlleleRandom(42);
        StringBuilder bits = new StringBuilder();
        for (int index = 0; index < 24; index++) bits.append(random.chooseSecond() ? '1' : '0');
        assertEquals("101001011010000101111000", bits.toString());
        var parent = genome("a", "b");
        var ruleset = builder(true).freeze();
        var child = MendelianInheritance.inherit(parent, parent, ruleset, new SeededAlleleRandom(42));
        String[] expected = {"ab", "ab", "ab", "ab", "ab", "ab", "aa", "ab", "ab", "bb", "ab", "aa"};
        for (Locus locus : Locus.values()) {
            String copies = expected[locus.ordinal()];
            assertEquals(pair(locus, copies.substring(0, 1), copies.substring(1)), child.loci().get(locus));
        }
        assertEquals(child, MendelianInheritance.inherit(parent, parent, ruleset, new SeededAlleleRandom(42)));
    }

    @Test
    void oneHundredThousandOffspringMatchAllLociAndAllPairwiseJointEvents() {
        int population = 100_000;
        int[][] counts = new int[Locus.values().length][3];
        int[][] joint = new int[Locus.values().length][Locus.values().length];
        boolean[] recessive = new boolean[Locus.values().length];
        var parent = genome("a", "b");
        var ruleset = builder(true).freeze();
        var random = new SeededAlleleRandom(0x47454e45534953L);
        for (int index = 0; index < population; index++) {
            var child = MendelianInheritance.inherit(parent, parent, ruleset, random);
            for (Locus locus : Locus.values()) {
                var pair = child.loci().get(locus);
                int bCopies = (pair.first().equals(allele(locus, "b")) ? 1 : 0)
                        + (pair.second().equals(allele(locus, "b")) ? 1 : 0);
                counts[locus.ordinal()][bCopies]++;
                recessive[locus.ordinal()] = bCopies == 2;
            }
            for (int first = 0; first < recessive.length; first++) {
                for (int second = first + 1; second < recessive.length; second++) {
                    if (recessive[first] && recessive[second]) joint[first][second]++;
                }
            }
        }
        for (Locus locus : Locus.values()) {
            assertBinomial(counts[locus.ordinal()][0], population, 0.25, locus + " aa");
            assertBinomial(counts[locus.ordinal()][1], population, 0.50, locus + " ab");
            assertBinomial(counts[locus.ordinal()][2], population, 0.25, locus + " bb");
        }
        for (Locus first : Locus.values()) {
            for (Locus second : Locus.values()) {
                if (first.ordinal() < second.ordinal()) {
                    assertBinomial(joint[first.ordinal()][second.ordinal()], population, 0.0625,
                            first + " bb AND " + second + " bb");
                }
            }
        }
    }

    private static void assertBinomial(int count, int population, double probability, String label) {
        double expected = population * probability;
        // Six binomial standard deviations; fixed seed, 102 comparisons, no test-to-test randomness.
        double tolerance = 6 * Math.sqrt(population * probability * (1 - probability));
        assertEquals(expected, count, tolerance, label);
    }
}
