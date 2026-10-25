package dev.ifelse.zirion.genesis.core.content.v2;

import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.GeneDefinition;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.genetics.v2.MendelianInheritance;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class HybridGraphTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(HybridGraphTest.class.getClassLoader());
    private static final HybridGraph GRAPH = BUNDLE.hybridGraph();
    private static final FormResolver RESOLVER = new FormResolver(GRAPH, BUNDLE.families());

    @Test
    void all112CatalogPairsResolveSymmetricallyWithTheExactGraphPin() {
        var source = JsonParser.parseString(new String(BUNDLE.documents()
                .get("Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject();
        int count = 0;
        for (var family : source.getAsJsonArray("families")) {
            for (var entry : family.getAsJsonObject().getAsJsonArray("allowedPairs")) {
                var pair = entry.getAsJsonObject();
                var a = GRAPH.identity(ContentId.parse(pair.get("parentA").getAsString()));
                var b = GRAPH.identity(ContentId.parse(pair.get("parentB").getAsString()));
                var child = GRAPH.historicalChild(a, b);
                assertEquals(pair.get("childForm").getAsString(), child.historicalForm().toString());
                assertEquals(child, GRAPH.historicalChild(b, a));
                assertEquals(BUNDLE.defaults().genetics().registries().hybridGraph().fingerprint(), child.hybridGraph());
                var union = new HashSet<>(GRAPH.validate(a).roots()); union.addAll(GRAPH.validate(b).roots());
                assertEquals(union, GRAPH.validate(child).roots());
                count++;
            }
        }
        assertEquals(112, count);
        assertEquals(28, GRAPH.forms().size());
        assertEquals(112, GRAPH.pairs().size());
    }

    @Test
    void backcrossLosesWolfSupportButPreservesHistoricalWolfoxAndThenTriad() {
        var schema = BUNDLE.families().get(id("canids"));
        var wolf = founder(schema, id("wolf")); var fox = founder(schema, id("fox"));
        var f1 = MendelianInheritance.inherit(wolf, fox, schema, () -> false);
        var f1History = GRAPH.historicalChild(GRAPH.identity(id("wolf")), GRAPH.identity(id("fox")));
        assertEquals(id("wolf_fox"), RESOLVER.resolve(f1History, f1).effectiveSignatureForm().id());
        // Canonical fox|wolf pairs choose fox; the pure fox parent also contributes fox at every locus.
        var backcross = MendelianInheritance.inherit(f1, fox, schema, () -> false);
        var history = GRAPH.historicalChild(f1History, GRAPH.identity(id("fox")));
        var resolved = RESOLVER.resolve(history, backcross);
        assertEquals(id("wolf_fox"), resolved.historicalForm().id());
        assertEquals(id("fox"), resolved.effectiveSignatureForm().id());
        assertEquals(Map.of(id("fox"), 6), resolved.founderCopyCounts());
        var next = MendelianInheritance.inherit(backcross, founder(schema, id("hyena")), schema, () -> false);
        var nextHistory = GRAPH.historicalChild(history, GRAPH.identity(id("hyena")));
        assertEquals(id("canid_triad"), nextHistory.historicalForm());
        assertEquals(id("fox_hyena"), RESOLVER.resolve(nextHistory, next).effectiveSignatureForm().id());
    }

    @Test
    void all2916OrderedHeritageCombinationsResolveByPositiveSupport() {
        int count = 0;
        for (var family : GRAPH.families().values()) {
            var schema = BUNDLE.families().get(family.id());
            var founders = new ArrayList<>(family.founders());
            var history = GRAPH.identity(GRAPH.formForRoots(family.id(), family.founders()).id());
            for (int encoded = 0; encoded < 729; encoded++) {
                int remainder = encoded;
                var pairs = new TreeMap<>(founder(schema, founders.getFirst()).genes());
                var expected = new TreeMap<ContentId, Integer>();
                for (var gene : FamilyGeneSchema.HERITAGE_GENES) {
                    ContentId a = founders.get(remainder % 3); remainder /= 3;
                    ContentId b = founders.get(remainder % 3); remainder /= 3;
                    expected.merge(a, 1, Integer::sum); expected.merge(b, 1, Integer::sum);
                    pairs.put(gene, new AllelePair(a, b));
                }
                var resolved = RESOLVER.resolve(history, schema.createGenome(pairs));
                assertEquals(expected, resolved.founderCopyCounts());
                assertEquals(expected.keySet(), resolved.effectiveSignatureForm().roots());
                assertEquals(family.id(), resolved.effectiveSignatureForm().familyId());
                assertEquals(history.historicalForm(), resolved.historicalForm().id());
                assertEquals(6, resolved.founderCopyCounts().values().stream().mapToInt(Integer::intValue).sum());
                count++;
            }
        }
        assertEquals(2916, count);
    }

    @Test
    void aSingleRemainingCopyStillSupportsItsLineage() {
        var schema = BUNDLE.families().get(id("canids"));
        var pairs = new TreeMap<>(founder(schema, id("fox")).genes());
        pairs.put(FamilyGeneSchema.HERITAGE_GENES.getFirst(), new AllelePair(id("wolf"), id("fox")));
        var result = RESOLVER.resolve(GRAPH.identity(id("wolf_fox")), schema.createGenome(pairs));
        assertEquals(Map.of(id("fox"), 5, id("wolf"), 1), result.founderCopyCounts());
        assertEquals(id("wolf_fox"), result.effectiveSignatureForm().id());
    }

    @Test
    void crossFamilyPairsAndUnknownFormsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> GRAPH.historicalChild(GRAPH.identity(id("wolf")), GRAPH.identity(id("horse"))));
        assertThrows(IllegalArgumentException.class, () -> GRAPH.identity(id("missing")));
        assertThrows(IllegalArgumentException.class, () -> GRAPH.formForRoots(id("canids"), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> GRAPH.formForRoots(id("canids"), Set.of(id("horse"))));
    }

    @Test
    void staleGraphIdsHashesAndVersionsCannotBeReinterpreted() {
        for (var reference : List.of(
                new RegistryFingerprint(new RulesetVersion(id("other_graph"), 1), GRAPH.reference().sha256()),
                new RegistryFingerprint(GRAPH.reference().reference(), "0".repeat(64)))) {
            var old = new FormIdentity(reference, id("wolf"));
            assertThrows(IllegalArgumentException.class, () -> GRAPH.validate(old));
            assertThrows(IllegalArgumentException.class, () -> GRAPH.historicalChild(old, GRAPH.identity(id("fox"))));
        }
        var future = new RegistryFingerprint(new RulesetVersion(id("hybrid_graph"), 2), GRAPH.reference().sha256());
        assertThrows(IllegalArgumentException.class, () -> new FormIdentity(future, id("wolf")));
    }

    @Test
    void genomeCannotIntroduceLineageOutsideItsHistoryOrFamily() {
        var canids = BUNDLE.families().get(id("canids"));
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(GRAPH.identity(id("wolf")), founder(canids, id("fox"))));
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(GRAPH.identity(id("wolf_fox")), founder(canids, id("hyena"))));
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(GRAPH.identity(id("horse")), founder(canids, id("wolf"))));
    }

    @Test
    void resolutionValidatesAllGenesAndExactSchemaPin() {
        var schema = BUNDLE.families().get(id("canids")); var genome = founder(schema, id("wolf"));
        var bad = new TreeMap<>(genome.genes()); bad.put(id("body_frame"), new AllelePair(id("unknown"), id("unknown")));
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(GRAPH.identity(id("wolf")), new Genome(schema.reference(), schema.familyId(), bad)));
        var pin = new RegistryFingerprint(schema.reference().reference(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(GRAPH.identity(id("wolf")), new Genome(pin, schema.familyId(), genome.genes())));
    }

    @Test
    void graphAndResolvedCollectionsRetainDefensiveImmutableCopies() {
        var families = new HashMap<>(GRAPH.families()); var forms = new HashMap<>(GRAPH.forms()); var edges = edges();
        var copy = new HybridGraph(GRAPH.reference(), families, forms, edges);
        families.clear(); forms.clear(); edges.clear();
        assertEquals(GRAPH.forms(), copy.forms()); assertEquals(GRAPH.pairs(), copy.pairs());
        assertThrows(UnsupportedOperationException.class, () -> copy.families().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.forms().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.pairs().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.form(id("wolf_fox")).roots().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.families().get(id("canids")).founders().clear());
        var roots = new HashSet<>(Set.of(id("wolf")));
        var form = new HybridGraph.Form(id("wolf"), id("canids"), id("quad_predator"), roots); roots.clear();
        assertEquals(Set.of(id("wolf")), form.roots());
        var result = RESOLVER.resolve(copy.identity(id("wolf")), founder(BUNDLE.families().get(id("canids")), id("wolf")));
        assertThrows(UnsupportedOperationException.class, () -> result.founderCopyCounts().clear());
    }

    @Test
    void missingDuplicateWrongChildAndCrossFamilyEdgesAreRejectedAtFreeze() {
        var missing = edges(); missing.removeFirst();
        assertThrows(IllegalArgumentException.class, () -> graph(GRAPH.forms(), missing));
        var duplicate = edges(); var edge = duplicate.getFirst();
        duplicate.add(new HybridGraph.Edge(edge.parentB(), edge.parentA(), edge.childForm()));
        assertThrows(IllegalArgumentException.class, () -> graph(GRAPH.forms(), duplicate));
        var wrongChild = edges(); wrongChild.set(0, new HybridGraph.Edge(id("wolf"), id("fox"), id("wolf")));
        assertThrows(IllegalArgumentException.class, () -> graph(GRAPH.forms(), wrongChild));
        var foreign = edges(); foreign.set(0, new HybridGraph.Edge(id("wolf"), id("horse"), id("wolf_fox")));
        assertThrows(IllegalArgumentException.class, () -> graph(GRAPH.forms(), foreign));
    }

    @Test
    void incompleteAmbiguousOrIncompatibleFormsAreRejectedAtFreeze() {
        var missing = new TreeMap<>(GRAPH.forms()); missing.remove(id("wolf"));
        assertThrows(IllegalArgumentException.class, () -> graph(missing, edges()));
        var duplicate = new TreeMap<>(GRAPH.forms());
        duplicate.put(id("duplicate"), new HybridGraph.Form(id("duplicate"), id("canids"), id("quad_predator"), Set.of(id("wolf"), id("fox"))));
        assertThrows(IllegalArgumentException.class, () -> graph(duplicate, edges()));
        for (var form : List.of(
                new HybridGraph.Form(id("wolf"), id("canids"), id("wrong_rig"), Set.of(id("wolf"))),
                new HybridGraph.Form(id("wolf"), id("canids"), id("quad_predator"), Set.of(id("horse"))),
                new HybridGraph.Form(id("wolf"), id("canids"), id("quad_predator"), Set.of(id("fox"))))) {
            var forms = new TreeMap<>(GRAPH.forms()); forms.put(id("wolf"), form);
            assertThrows(IllegalArgumentException.class, () -> graph(forms, edges()));
        }
    }

    @Test
    void resolverRequiresMatchingSchemaFamilyAndFounders() {
        var schemas = new TreeMap<>(BUNDLE.families()); schemas.remove(id("canids"));
        assertThrows(IllegalArgumentException.class, () -> new FormResolver(GRAPH, schemas));
        var wrong = new TreeMap<>(BUNDLE.families()); wrong.put(id("canids"), wrong.get(id("runners")));
        assertThrows(IllegalArgumentException.class, () -> new FormResolver(GRAPH, wrong));
        var schema = BUNDLE.families().get(id("canids"));
        var founders = Set.of(id("wolf"), id("fox"), id("other"));
        var genes = new TreeMap<>(schema.genes());
        for (ContentId gene : FamilyGeneSchema.HERITAGE_GENES) {
            genes.put(gene, new GeneDefinition(gene, founders, GeneDefinition.Kind.HERITAGE));
        }
        var changed = new TreeMap<>(BUNDLE.families());
        changed.put(id("canids"), new FamilyGeneSchema(schema.reference(), schema.familyId(), founders, genes));
        assertThrows(IllegalArgumentException.class, () -> new FormResolver(GRAPH, changed));
        var families = new TreeMap<>(GRAPH.families());
        families.put(id("canids"), new HybridGraph.Family(id("canids"), id("quad_predator"), Set.of(id("wolf"), id("fox"), id("other"))));
        assertThrows(IllegalArgumentException.class, () -> new HybridGraph(GRAPH.reference(), families, GRAPH.forms(), edges()));
    }

    @Test
    void bundleCannotPublishGraphOrSchemaWithDifferentPins() {
        var stalePin = new RegistryFingerprint(GRAPH.reference().reference(), "0".repeat(64));
        var staleGraph = new HybridGraph(stalePin, GRAPH.families(), GRAPH.forms(), edges());
        assertThrows(IllegalArgumentException.class, () -> new CatalogBundle(BUNDLE.defaults(), BUNDLE.families(),
                staleGraph, BUNDLE.expressionRules(), BUNDLE.mutations(), BUNDLE.signaturePassives(), BUNDLE.contentCatalogId(), BUNDLE.contentCatalogSha256(), BUNDLE.documents()));
        var original = BUNDLE.families().get(id("canids"));
        var schemas = new TreeMap<>(BUNDLE.families());
        schemas.put(id("canids"), new FamilyGeneSchema(new RegistryFingerprint(original.reference().reference(), "0".repeat(64)),
                original.familyId(), original.founders(), original.genes()));
        assertThrows(IllegalArgumentException.class, () -> new CatalogBundle(BUNDLE.defaults(), schemas,
                GRAPH, BUNDLE.expressionRules(), BUNDLE.mutations(), BUNDLE.signaturePassives(), BUNDLE.contentCatalogId(), BUNDLE.contentCatalogSha256(), BUNDLE.documents()));
    }

    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }

    private static Genome founder(FamilyGeneSchema schema, ContentId founder) {
        var pairs = new TreeMap<ContentId, AllelePair>();
        schema.genes().forEach((gene, definition) -> {
            ContentId allele = definition.kind() == GeneDefinition.Kind.HERITAGE
                    ? founder : definition.variants().iterator().next();
            pairs.put(gene, new AllelePair(allele, allele));
        });
        return schema.createGenome(pairs);
    }

    private static ArrayList<HybridGraph.Edge> edges() {
        var edges = new ArrayList<HybridGraph.Edge>();
        GRAPH.pairs().forEach((pair, child) -> edges.add(new HybridGraph.Edge(pair.first(), pair.second(), child)));
        return edges;
    }

    private static HybridGraph graph(Map<ContentId, HybridGraph.Form> forms, List<HybridGraph.Edge> edges) {
        return new HybridGraph(GRAPH.reference(), GRAPH.families(), forms, edges);
    }
}
