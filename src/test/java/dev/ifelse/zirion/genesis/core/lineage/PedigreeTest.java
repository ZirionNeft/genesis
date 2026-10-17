package dev.ifelse.zirion.genesis.core.lineage;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.lineage.Pedigree.Uncertainty.*;
import static org.junit.jupiter.api.Assertions.*;

class PedigreeTest {
    private static UUID id(int number) { return new UUID(0, number); }
    private static PedigreeEntry founder(int n) { return PedigreeEntry.founder(id(n)); }
    private static PedigreeEntry child(int n, int a, int b) { return PedigreeEntry.child(id(n), id(a), id(b)); }

    private static ArrayList<PedigreeEntry> family() {
        return new ArrayList<>(List.of(founder(1), founder(2), founder(3), founder(4), founder(5),
                child(10, 1, 2), child(11, 1, 2), child(12, 1, 3),
                child(20, 10, 3), child(21, 11, 4), child(30, 10, 11), child(31, 10, 11), child(40, 30, 5)));
    }

    private static void exact(Pedigree graph, int a, int b, String coefficient) {
        var estimate = graph.kinship(id(a), id(b));
        assertEquals(new BigDecimal(coefficient), estimate.lowerBound());
        assertEquals(estimate.lowerBound(), estimate.upperBound());
        assertFalse(estimate.partial());
        assertEquals(estimate, graph.kinship(id(b), id(a)));
    }

    @Test
    void allFiveGddRelationshipsHaveTheCorrectOffspringInbreedingCoefficient() {
        var graph = new Pedigree(family());
        exact(graph, 1, 3, "0");
        exact(graph, 20, 21, "0.0625");
        exact(graph, 10, 12, "0.125");
        exact(graph, 1, 10, "0.25");
        exact(graph, 10, 11, "0.25");
        assertEquals(OptionalInt.of(0), graph.generation(id(1)));
        assertEquals(OptionalInt.of(1), graph.generation(id(10)));
        assertEquals(OptionalInt.of(2), graph.generation(id(30)));
        assertEquals(OptionalInt.of(3), graph.generation(id(40)));
    }

    @Test
    void inbredAncestorsUseTheirOwnInbreedingInTheDiagonal() {
        var graph = new Pedigree(family());
        exact(graph, 30, 30, "0.625");
        exact(graph, 30, 31, "0.375");
        exact(graph, 30, 40, "0.3125");
    }

    @Test
    void unknownParentsMissingRecordsAndSharedUnresolvedIdentitiesRemainPartial() {
        var graph = new Pedigree(List.of(founder(1), PedigreeEntry.unknown(id(2)),
                new PedigreeEntry(id(10), Optional.of(id(1)), Optional.empty(), false),
                new PedigreeEntry(id(11), Optional.of(id(1)), Optional.empty(), false),
                child(20, 90, 1), child(21, 90, 2)));
        var unknown = graph.kinship(id(1), id(2));
        assertEquals(BigDecimal.ZERO, unknown.lowerBound());
        assertEquals(BigDecimal.ONE, unknown.upperBound());
        assertEquals(Set.of(UNKNOWN_PARENT), unknown.uncertainty());
        var siblings = graph.kinship(id(10), id(11));
        assertEquals(new BigDecimal("0.125"), siblings.lowerBound());
        assertTrue(siblings.partial());
        var unresolvedShared = graph.kinship(id(20), id(21));
        assertEquals(new BigDecimal("0.125"), unresolvedShared.lowerBound());
        assertEquals(Set.of(UNKNOWN_PARENT, MISSING_RECORD), unresolvedShared.uncertainty());
        assertTrue(graph.kinship(id(1), id(999)).uncertainty().contains(MISSING_RECORD));
        assertEquals(OptionalInt.empty(), graph.generation(id(20)));
        assertEquals(OptionalInt.empty(), graph.generation(id(999)));
    }

    @Test
    void depthLimitReportsLostKnowledgeAndAdditionalGenerationsRecoverIt() {
        var graph = new Pedigree(family());
        var shallow = graph.kinship(id(20), id(21), 1);
        assertEquals(BigDecimal.ZERO, shallow.lowerBound());
        assertEquals(BigDecimal.ONE, shallow.upperBound());
        assertEquals(Set.of(DEPTH_LIMIT), shallow.uncertainty());
        var full = graph.kinship(id(20), id(21), 2);
        assertEquals(new BigDecimal("0.0625"), full.lowerBound());
        assertFalse(full.partial());
        // At depth 0 both requested identities are still retained; a known parent link is usable.
        var direct = graph.kinship(id(1), id(10), 0);
        assertEquals(new BigDecimal("0.25"), direct.lowerBound());
        assertTrue(direct.partial());
        assertThrows(IllegalArgumentException.class, () -> graph.kinship(id(1), id(2), -1));
    }

    @Test
    void duplicatesSelfParentsRepeatedParentsAndCyclesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Pedigree(List.of(founder(1), founder(1))));
        assertThrows(IllegalArgumentException.class, () -> child(1, 1, 2));
        assertThrows(IllegalArgumentException.class, () -> child(3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PedigreeEntry(id(3), Optional.of(id(1)), Optional.empty(), true));
        assertThrows(IllegalArgumentException.class, () -> new Pedigree(List.of(child(1, 2, 3), child(2, 1, 4))));
        var disconnected = family();
        disconnected.add(child(100, 101, 102));
        disconnected.add(child(101, 100, 103));
        assertThrows(IllegalArgumentException.class, () -> new Pedigree(disconnected));
    }

    @Test
    void registrationAndParentOrderDoNotChangeResultsAndSnapshotsAreImmutable() {
        var entries = family();
        var graph = new Pedigree(entries);
        Collections.reverse(entries);
        var reversed = entries.stream().map(entry -> new PedigreeEntry(entry.creatureId(), entry.parentB(),
                entry.parentA(), entry.referenceFounder())).toList();
        var other = new Pedigree(reversed);
        entries.clear();
        for (int a : new int[]{1, 10, 20, 30, 40}) {
            for (int b : new int[]{1, 10, 20, 30, 40}) {
                assertEquals(graph.kinship(id(a), id(b)), other.kinship(id(a), id(b)));
            }
        }
        assertThrows(UnsupportedOperationException.class, () -> graph.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.kinship(id(999), id(1)).uncertainty().clear());
    }

    @Test
    void longGraphsAreIterativeAndBroadCalculationsFailAtAnExplicitNodeBudget() {
        var chain = new ArrayList<>(List.of(founder(1), founder(2)));
        int previous = 2;
        for (int i = 3; i <= 5_002; i++) {
            chain.add(child(i, previous, 1));
            previous = i;
        }
        var graph = new Pedigree(chain);
        assertEquals(OptionalInt.of(5_000), graph.generation(id(previous)));
        assertTrue(graph.kinship(id(previous), id(1)).partial());
        var tree = new ArrayList<PedigreeEntry>();
        for (int i = 1; i <= 1_023; i++) tree.add(i <= 511 ? child(i, i * 2, i * 2 + 1) : founder(i));
        var broad = new Pedigree(tree);
        assertThrows(IllegalArgumentException.class, () -> broad.kinship(id(1), id(1), 9));
        assertTrue(broad.kinship(id(1), id(1)).partial());
    }

    @Test
    void oneHundredThousandGeneDropsIndependentlyConfirmKinshipForAnInbredLine() {
        var graph = new Pedigree(family());
        double expected = graph.kinship(id(30), id(31)).lowerBound().doubleValue();
        int trials = 100_000;
        int identicalByDescent = 0;
        Random random = new Random(0x5045444947524545L);
        // 0/1 are unrelated founders, 2/3 their children, 4/5 offspring of those siblings.
        int[][] parentIndices = {{}, {}, {0, 1}, {0, 1}, {2, 3}, {2, 3}};
        int[][] genes = new int[6][2];
        for (int trial = 0; trial < trials; trial++) {
            genes[0][0] = 0; genes[0][1] = 1;
            genes[1][0] = 2; genes[1][1] = 3;
            for (int child = 2; child < genes.length; child++) {
                genes[child][0] = genes[parentIndices[child][0]][random.nextInt(2)];
                genes[child][1] = genes[parentIndices[child][1]][random.nextInt(2)];
            }
            if (genes[4][random.nextInt(2)] == genes[5][random.nextInt(2)]) identicalByDescent++;
        }
        assertEquals(0.375, expected);
        assertEquals(trials * expected, identicalByDescent, 6 * Math.sqrt(trials * expected * (1 - expected)));
    }
}
