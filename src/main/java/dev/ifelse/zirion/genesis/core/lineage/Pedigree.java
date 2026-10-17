package dev.ifelse.zirion.genesis.core.lineage;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** Immutable DAG and bounded autosomal kinship calculation; no claim about unrecorded real ancestors. */
public final class Pedigree {
    public static final int ALGORITHM_VERSION = 1;
    public static final int DEFAULT_GENERATIONS = 4;
    public static final int MAX_CALCULATION_NODES = 512;

    public enum Uncertainty { UNKNOWN_PARENT, MISSING_RECORD, DEPTH_LIMIT }

    public record Estimate(BigDecimal lowerBound, BigDecimal upperBound, Set<Uncertainty> uncertainty,
                           int exploredNodes, int maxGenerations) {
        public Estimate {
            Objects.requireNonNull(lowerBound, "lowerBound");
            Objects.requireNonNull(upperBound, "upperBound");
            uncertainty = Set.copyOf(uncertainty);
            if (lowerBound.signum() < 0 || lowerBound.compareTo(upperBound) > 0
                    || upperBound.compareTo(BigDecimal.ONE) > 0 || exploredNodes < 1 || maxGenerations < 0) {
                throw new IllegalArgumentException("Invalid kinship estimate");
            }
            if (uncertainty.isEmpty() && lowerBound.compareTo(upperBound) != 0) {
                throw new IllegalArgumentException("Complete pedigree estimate must have equal bounds");
            }
            lowerBound = lowerBound.stripTrailingZeros();
            upperBound = upperBound.stripTrailingZeros();
        }

        public boolean partial() { return !uncertainty.isEmpty(); }
    }

    private record Visit(UUID id, int depth) {}

    private final Map<UUID, PedigreeEntry> entries;
    private final Map<UUID, OptionalInt> generations;

    /** Missing parent records remain unresolved references; duplicates and cycles are errors. */
    public Pedigree(Collection<PedigreeEntry> source) {
        TreeMap<UUID, PedigreeEntry> copy = new TreeMap<>();
        for (PedigreeEntry entry : source) {
            Objects.requireNonNull(entry, "pedigree entry");
            if (copy.putIfAbsent(entry.creatureId(), entry) != null) {
                throw new IllegalArgumentException("Duplicate pedigree ID: " + entry.creatureId());
            }
        }
        entries = Collections.unmodifiableMap(copy);
        Map<UUID, List<UUID>> edges = new TreeMap<>();
        copy.forEach((id, entry) -> edges.put(id, parents(entry).stream().filter(copy::containsKey).toList()));
        List<UUID> ordered = topologicalOrder(edges);
        Map<UUID, OptionalInt> generation = new TreeMap<>();
        for (UUID id : ordered) {
            PedigreeEntry entry = copy.get(id);
            OptionalInt value = OptionalInt.empty();
            if (entry.referenceFounder()) value = OptionalInt.of(0);
            else if (entry.parentA().isPresent() && entry.parentB().isPresent()) {
                OptionalInt a = generation.getOrDefault(entry.parentA().get(), OptionalInt.empty());
                OptionalInt b = generation.getOrDefault(entry.parentB().get(), OptionalInt.empty());
                if (a.isPresent() && b.isPresent()) value = OptionalInt.of(Math.addExact(1, Math.max(a.getAsInt(), b.getAsInt())));
            }
            generation.put(id, value);
        }
        generations = Collections.unmodifiableMap(generation);
    }

    public Map<UUID, PedigreeEntry> entries() { return entries; }

    public OptionalInt generation(UUID id) {
        return generations.getOrDefault(Objects.requireNonNull(id), OptionalInt.empty());
    }

    public Estimate kinship(UUID a, UUID b) { return kinship(a, b, DEFAULT_GENERATIONS); }

    /** The returned phi(a,b) is F of their child. Self-kinship is mathematical, not breeding permission. */
    public Estimate kinship(UUID a, UUID b, int maxGenerations) {
        Objects.requireNonNull(a, "parent A");
        Objects.requireNonNull(b, "parent B");
        if (maxGenerations < 0) throw new IllegalArgumentException("Generation limit must be nonnegative");
        Map<UUID, Integer> reached = new TreeMap<>();
        var queue = new ArrayDeque<Visit>();
        reached.put(a, 0);
        reached.put(b, 0);
        queue.add(new Visit(a, 0));
        if (!a.equals(b)) queue.add(new Visit(b, 0));
        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            if (visit.depth() >= maxGenerations) continue;
            for (UUID parent : parents(entries.get(visit.id()))) {
                if (!reached.containsKey(parent)) {
                    if (reached.size() >= MAX_CALCULATION_NODES) throw new IllegalArgumentException("Pedigree calculation node limit exceeded");
                    reached.put(parent, visit.depth() + 1);
                    queue.add(new Visit(parent, visit.depth() + 1));
                }
            }
        }
        EnumSet<Uncertainty> uncertainty = EnumSet.noneOf(Uncertainty.class);
        Map<UUID, List<UUID>> edges = new TreeMap<>();
        for (UUID id : reached.keySet()) {
            PedigreeEntry entry = entries.get(id);
            if (entry == null) uncertainty.add(Uncertainty.MISSING_RECORD);
            else if (!entry.referenceFounder() && (entry.parentA().isEmpty() || entry.parentB().isEmpty())) {
                uncertainty.add(Uncertainty.UNKNOWN_PARENT);
            }
            List<UUID> parents = parents(entry);
            if (parents.stream().anyMatch(parent -> !reached.containsKey(parent))) uncertainty.add(Uncertainty.DEPTH_LIMIT);
            edges.put(id, parents.stream().filter(reached::containsKey).toList());
        }
        List<UUID> order = topologicalOrder(edges);
        Map<UUID, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) index.put(order.get(i), i);
        BigDecimal[][] matrix = new BigDecimal[order.size()][order.size()];
        for (int i = 0; i < order.size(); i++) {
            PedigreeEntry entry = entries.get(order.get(i));
            Integer p = entry == null ? null : entry.parentA().map(index::get).orElse(null);
            Integer q = entry == null ? null : entry.parentB().map(index::get).orElse(null);
            for (int j = 0; j < i; j++) {
                BigDecimal value = (p == null ? BigDecimal.ZERO : matrix[p][j])
                        .add(q == null ? BigDecimal.ZERO : matrix[q][j]).divide(BigDecimal.TWO);
                matrix[i][j] = value;
                matrix[j][i] = value;
            }
            BigDecimal parentalKinship = p == null || q == null ? BigDecimal.ZERO : matrix[p][q];
            matrix[i][i] = BigDecimal.ONE.add(parentalKinship).divide(BigDecimal.TWO);
        }
        BigDecimal lower = matrix[index.get(a)][index.get(b)];
        return new Estimate(lower, uncertainty.isEmpty() ? lower : BigDecimal.ONE,
                uncertainty, reached.size(), maxGenerations);
    }

    private static List<UUID> parents(PedigreeEntry entry) {
        if (entry == null) return List.of();
        return Stream.concat(entry.parentA().stream(), entry.parentB().stream()).toList();
    }

    /** Iterative ordering validates long graphs without recursion and never depends on insertion order. */
    private static List<UUID> topologicalOrder(Map<UUID, List<UUID>> edges) {
        Map<UUID, Integer> pending = new HashMap<>();
        Map<UUID, List<UUID>> children = new HashMap<>();
        PriorityQueue<UUID> ready = new PriorityQueue<>();
        edges.forEach((id, parents) -> {
            pending.put(id, parents.size());
            if (parents.isEmpty()) ready.add(id);
            for (UUID parent : parents) children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(id);
        });
        var ordered = new ArrayList<UUID>();
        while (!ready.isEmpty()) {
            UUID id = ready.remove();
            ordered.add(id);
            for (UUID child : children.getOrDefault(id, List.of())) {
                int remaining = pending.compute(child, (key, count) -> count - 1);
                if (remaining == 0) ready.add(child);
            }
        }
        if (ordered.size() != edges.size()) throw new IllegalArgumentException("Pedigree contains a cycle");
        return ordered;
    }
}
