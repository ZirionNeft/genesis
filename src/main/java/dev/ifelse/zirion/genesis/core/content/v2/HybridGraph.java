package dev.ifelse.zirion.genesis.core.content.v2;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Frozen initial graph: three founders and seven forms per family, explicit symmetric edges only. */
public final class HybridGraph {
    public record Family(ContentId id, ContentId rigId, Set<ContentId> founders) {
        public Family {
            Objects.requireNonNull(id); Objects.requireNonNull(rigId);
            founders = immutableSet(founders);
            if (founders.size() != 3) throw new IllegalArgumentException("Expected three founding species per family");
        }
    }

    /** Logical rig only; this does not establish availability of a vanilla NPC or visual adapter. */
    public record Form(ContentId id, ContentId familyId, ContentId rigId, Set<ContentId> roots) {
        public Form {
            Objects.requireNonNull(id); Objects.requireNonNull(familyId); Objects.requireNonNull(rigId);
            roots = immutableSet(roots);
            if (roots.isEmpty() || roots.size() > 3) throw new IllegalArgumentException("Expected one to three roots");
        }
    }

    public record Pair(ContentId first, ContentId second) implements Comparable<Pair> {
        public Pair {
            Objects.requireNonNull(first); Objects.requireNonNull(second);
            if (first.compareTo(second) > 0) { ContentId swap = first; first = second; second = swap; }
        }

        @Override public int compareTo(Pair other) {
            int order = first.compareTo(other.first);
            return order != 0 ? order : second.compareTo(other.second);
        }
    }

    /** A list is accepted at construction so reversed duplicate edges cannot disappear in a map. */
    public record Edge(ContentId parentA, ContentId parentB, ContentId childForm) {
        public Edge { Objects.requireNonNull(parentA); Objects.requireNonNull(parentB); Objects.requireNonNull(childForm); }
    }

    private final RegistryFingerprint reference;
    private final Map<ContentId, Family> families;
    private final Map<ContentId, Form> forms;
    private final Map<Pair, ContentId> pairs;
    private final Map<ContentId, Map<Set<ContentId>, ContentId>> rootsIndex;

    public HybridGraph(RegistryFingerprint reference, Map<ContentId, Family> families,
                       Map<ContentId, Form> forms, List<Edge> edges) {
        this.reference = Objects.requireNonNull(reference, "reference");
        if (reference.reference().version() != 1) throw new IllegalArgumentException("Only hybrid graph v1 is supported");
        this.families = Collections.unmodifiableSortedMap(new TreeMap<>(families));
        this.forms = Collections.unmodifiableSortedMap(new TreeMap<>(forms));
        if (this.families.isEmpty()) throw new IllegalArgumentException("Graph has no families");
        var roots = new TreeMap<ContentId, Map<Set<ContentId>, ContentId>>();
        var allFounders = new HashSet<ContentId>();
        for (var entry : this.families.entrySet()) {
            Family family = Objects.requireNonNull(entry.getValue());
            if (!entry.getKey().equals(family.id())) throw new IllegalArgumentException("Family map key differs from ID");
            for (ContentId founder : family.founders()) {
                if (!allFounders.add(founder)) throw new IllegalArgumentException("Founder belongs to multiple families");
            }
            roots.put(family.id(), new HashMap<>());
        }
        for (var entry : this.forms.entrySet()) {
            Form form = Objects.requireNonNull(entry.getValue());
            if (!entry.getKey().equals(form.id())) throw new IllegalArgumentException("Form map key differs from ID");
            Family family = this.families.get(form.familyId());
            if (family == null || !family.rigId().equals(form.rigId()) || !family.founders().containsAll(form.roots())) {
                throw new IllegalArgumentException("Unknown family, incompatible rig or foreign founder");
            }
            if (form.roots().size() == 1 && !form.roots().contains(form.id())) {
                throw new IllegalArgumentException("Base form ID must equal its founder ID");
            }
            if (roots.get(form.familyId()).putIfAbsent(form.roots(), form.id()) != null) {
                throw new IllegalArgumentException("Duplicate root subset");
            }
        }
        for (var index : roots.values()) {
            if (index.size() != 7) throw new IllegalArgumentException("Expected all seven nonempty founder subsets");
        }
        var pairCopy = new TreeMap<Pair, ContentId>();
        for (Edge edge : List.copyOf(edges)) {
            Form a = form(edge.parentA()), b = form(edge.parentB()), child = form(edge.childForm());
            if (!a.familyId().equals(b.familyId()) || !a.familyId().equals(child.familyId())) {
                throw new IllegalArgumentException("Cross-family edge is forbidden");
            }
            var union = new TreeSet<>(a.roots()); union.addAll(b.roots());
            if (!union.equals(child.roots())) throw new IllegalArgumentException("Child roots must be the parent union");
            if (pairCopy.putIfAbsent(new Pair(a.id(), b.id()), child.id()) != null) {
                throw new IllegalArgumentException("Duplicate unordered pair");
            }
        }
        // Every pair is required for the initial graph; missing edges are never inferred from roots.
        for (ContentId family : this.families.keySet()) {
            long count = pairCopy.keySet().stream().filter(pair -> form(pair.first()).familyId().equals(family)).count();
            if (count != 28) throw new IllegalArgumentException("Expected all 28 explicit unordered pairs per family");
        }
        this.pairs = Collections.unmodifiableSortedMap(pairCopy);
        var frozenRoots = new TreeMap<ContentId, Map<Set<ContentId>, ContentId>>();
        roots.forEach((family, index) -> frozenRoots.put(family, Map.copyOf(index)));
        this.rootsIndex = Collections.unmodifiableSortedMap(frozenRoots);
    }

    public RegistryFingerprint reference() { return reference; }
    public Map<ContentId, Family> families() { return families; }
    public Map<ContentId, Form> forms() { return forms; }
    public Map<Pair, ContentId> pairs() { return pairs; }

    public Form form(ContentId id) {
        Form form = forms.get(Objects.requireNonNull(id));
        if (form == null) throw new IllegalArgumentException("Unknown historical form: " + id);
        return form;
    }

    public Form formForRoots(ContentId familyId, Set<ContentId> roots) {
        Map<Set<ContentId>, ContentId> index = rootsIndex.get(Objects.requireNonNull(familyId));
        ContentId id = index == null ? null : index.get(Set.copyOf(roots));
        if (id == null) throw new IllegalArgumentException("Unsupported founder subset");
        return form(id);
    }

    public FormIdentity identity(ContentId historicalForm) {
        return new FormIdentity(reference, form(historicalForm).id());
    }

    public Form validate(FormIdentity identity) {
        Objects.requireNonNull(identity);
        if (!reference.equals(identity.hybridGraph())) throw new IllegalArgumentException("Historical form requires another graph ID, version or SHA-256");
        return form(identity.historicalForm());
    }

    /** Historical metadata calculation only; genome, game eligibility and resource checks belong to callers. */
    public FormIdentity historicalChild(FormIdentity parentA, FormIdentity parentB) {
        Form a = validate(parentA), b = validate(parentB);
        ContentId child = pairs.get(new Pair(a.id(), b.id()));
        if (child == null) throw new IllegalArgumentException("No explicit pair for these historical forms");
        return identity(child);
    }

    private static Set<ContentId> immutableSet(Set<ContentId> values) {
        var copy = new TreeSet<ContentId>();
        for (ContentId value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSortedSet(copy);
    }
}
