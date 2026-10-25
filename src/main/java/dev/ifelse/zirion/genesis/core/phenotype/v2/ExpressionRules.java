package dev.ifelse.zirion.genesis.core.phenotype.v2;

import dev.ifelse.zirion.genesis.core.content.v2.HybridGraph;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.GeneDefinition;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Executable immutable v2 tables. Loaded values are the sole balance source; no built-in allele rules. */
public record ExpressionRules(References references, Policy policy, Map<ContentId, GeneRules> genes,
                              Map<ContentId, StatValues> founderStats, Map<String, ContentId> spellMap) {
    public static final ContentId ARCANA = ContentId.parse("genesis:arcana");
    public static final ContentId ELEMENT = ContentId.parse("genesis:element");

    public record References(RegistryFingerprint expression, RegistryFingerprint geneSchema,
                             RegistryFingerprint hybridGraph, RegistryFingerprint morphology,
                             ContentId contentCatalogId, String contentCatalogSha256) {
        public References {
            Objects.requireNonNull(expression); Objects.requireNonNull(geneSchema);
            Objects.requireNonNull(hybridGraph); Objects.requireNonNull(morphology);
            Objects.requireNonNull(contentCatalogId); Objects.requireNonNull(contentCatalogSha256);
            if (expression.reference().version() != 2 || geneSchema.reference().version() != 2
                    || hybridGraph.reference().version() != 1 || morphology.reference().version() != 2
                    || !contentCatalogSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Unsupported expression dependencies");
            }
        }
    }

    public record Policy(BigDecimal multiplierMin, BigDecimal multiplierMax, StatValues minimum, int speedRoundPlaces) {
        public Policy {
            Objects.requireNonNull(multiplierMin); Objects.requireNonNull(multiplierMax); Objects.requireNonNull(minimum);
            if (multiplierMin.signum() < 0 || multiplierMin.compareTo(multiplierMax) > 0 || speedRoundPlaces != 2) {
                throw new IllegalArgumentException("Invalid genetic stat policy");
            }
        }
    }

    public record PairRule(String state, Map<Stat, BigDecimal> percentDelta, Map<Stat, BigDecimal> flatDelta,
                           Map<String, BigDecimal> utility, Map<String, String> visual) {
        public PairRule {
            if (Objects.requireNonNull(state).isEmpty()) throw new IllegalArgumentException("Empty expressed state");
            percentDelta = statEffects(percentDelta); flatDelta = statEffects(flatDelta);
            utility = sorted(utility); visual = sorted(visual);
        }
    }

    public record GeneRules(Set<ContentId> families, Optional<String> visualSlot, Map<AllelePair, PairRule> pairs) {
        public GeneRules {
            families = Set.copyOf(families); Objects.requireNonNull(visualSlot); pairs = Map.copyOf(pairs);
            if (families.isEmpty() || pairs.isEmpty()) throw new IllegalArgumentException("Empty gene rules");
        }
    }

    public ExpressionRules {
        Objects.requireNonNull(references); Objects.requireNonNull(policy);
        genes = sorted(genes); founderStats = sorted(founderStats); spellMap = sorted(spellMap);
    }

    /** Checks completeness and compatibility before a registry bundle is published. */
    public void validate(HybridGraph graph, Map<ContentId, FamilyGeneSchema> schemas) {
        if (!references.hybridGraph().equals(graph.reference()) || !schemas.keySet().equals(graph.families().keySet())) {
            throw new IllegalArgumentException("Expression requires another graph or family set");
        }
        var founders = new HashSet<ContentId>();
        var expectedGenes = new HashSet<ContentId>();
        var utilityOwners = new HashSet<String>();
        var visualOwners = new HashSet<String>();
        for (FamilyGeneSchema schema : schemas.values()) {
            if (!references.geneSchema().equals(schema.reference())
                    || !schema.founders().equals(graph.families().get(schema.familyId()).founders())) {
                throw new IllegalArgumentException("Expression requires another gene schema");
            }
            founders.addAll(schema.founders());
            for (GeneDefinition definition : schema.genes().values()) {
                if (definition.kind() == GeneDefinition.Kind.HERITAGE) continue;
                expectedGenes.add(definition.id());
                GeneRules rules = genes.get(definition.id());
                if (rules == null || !rules.families().contains(schema.familyId())) throw new IllegalArgumentException("Missing family gene rules");
                var expectedPairs = new HashSet<AllelePair>();
                for (ContentId a : definition.variants()) for (ContentId b : definition.variants()) expectedPairs.add(new AllelePair(a, b));
                if (!rules.pairs().keySet().equals(expectedPairs)) throw new IllegalArgumentException("Incomplete gene pair rules");
            }
        }
        if (!founderStats.keySet().equals(founders) || !genes.keySet().equals(expectedGenes)) {
            throw new IllegalArgumentException("Expected founder-only bases and all trait rules");
        }
        for (var entry : genes.entrySet()) {
            GeneRules rules = entry.getValue();
            var applicable = new HashSet<ContentId>();
            for (FamilyGeneSchema schema : schemas.values()) if (schema.genes().containsKey(entry.getKey())) applicable.add(schema.familyId());
            if (!applicable.equals(rules.families())) throw new IllegalArgumentException("Gene family applicability differs");
            Set<String> utilityKeys = rules.pairs().values().iterator().next().utility().keySet();
            for (String name : utilityKeys) if (!utilityOwners.add(name)) throw new IllegalArgumentException("Ambiguous utility composition");
            if (rules.visualSlot().isPresent() && !visualOwners.add(rules.visualSlot().get())) throw new IllegalArgumentException("Ambiguous visual slot");
            for (PairRule rule : rules.pairs().values()) {
                Set<String> expectedSlots = rules.visualSlot().map(Set::of).orElse(Set.of());
                if (!rule.visual().keySet().equals(expectedSlots) || rule.visual().values().stream().anyMatch(String::isEmpty)
                        || !rule.utility().keySet().equals(utilityKeys)) {
                    throw new IllegalArgumentException("Missing or ambiguous explicit visual/utility state");
                }
            }
        }
        if (!genes.containsKey(ARCANA) || !genes.containsKey(ELEMENT)) throw new IllegalArgumentException("Missing innate potential rules");
        for (PairRule rule : genes.get(ARCANA).pairs().values()) {
            if (!Set.of("awake", "quiet").contains(rule.state())) throw new IllegalArgumentException("Unsupported arcana state");
        }
        var elements = new HashSet<String>();
        genes.get(ELEMENT).pairs().values().forEach(rule -> elements.add(rule.state()));
        if (!spellMap.keySet().equals(elements)) throw new IllegalArgumentException("Incomplete innate spell map");
    }

    private static Map<Stat, BigDecimal> statEffects(Map<Stat, BigDecimal> source) {
        var copy = new EnumMap<Stat, BigDecimal>(Stat.class);
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static <K extends Comparable<? super K>, V> Map<K, V> sorted(Map<K, V> source) {
        var copy = new TreeMap<K, V>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableSortedMap(copy);
    }
}
