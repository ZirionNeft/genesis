package dev.ifelse.zirion.genesis.core.content.v2;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Pure form resolution from exact graph/schema snapshots. No pedigree weights, role, asset selection or RNG. */
public final class FormResolver {
    public record Result(HybridGraph.Form historicalForm, HybridGraph.Form effectiveSignatureForm,
                         Map<ContentId, Integer> founderCopyCounts) {
        public Result {
            Objects.requireNonNull(historicalForm); Objects.requireNonNull(effectiveSignatureForm);
            founderCopyCounts = Collections.unmodifiableSortedMap(new TreeMap<>(founderCopyCounts));
        }
    }

    private final HybridGraph graph;
    private final Map<ContentId, FamilyGeneSchema> schemas;

    public FormResolver(HybridGraph graph, Map<ContentId, FamilyGeneSchema> schemas) {
        this.graph = Objects.requireNonNull(graph);
        this.schemas = Map.copyOf(schemas);
        if (!graph.families().keySet().equals(this.schemas.keySet())) throw new IllegalArgumentException("Graph and schema families differ");
        for (var entry : this.schemas.entrySet()) {
            FamilyGeneSchema schema = entry.getValue();
            if (!entry.getKey().equals(schema.familyId()) || !schema.founders().equals(graph.families().get(entry.getKey()).founders())) {
                throw new IllegalArgumentException("Graph and schema founders differ");
            }
        }
    }

    /** Positive support of all six HERITAGE copies selects the effective node, with no dominance threshold. */
    public Result resolve(FormIdentity identity, Genome genome) {
        HybridGraph.Form history = graph.validate(identity);
        FamilyGeneSchema schema = schemas.get(history.familyId());
        Map<ContentId, Integer> counts = schema.founderCopyCounts(genome); // validates every gene and exact schema pin
        if (!history.roots().containsAll(counts.keySet())) throw new IllegalArgumentException("Genome contains a lineage outside its historical roots");
        HybridGraph.Form effective = graph.formForRoots(history.familyId(), counts.keySet());
        return new Result(history, effective, counts);
    }
}
