package dev.ifelse.zirion.genesis.core.role;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static dev.ifelse.zirion.genesis.core.role.SignaturePassiveResolver.Match;
import static org.junit.jupiter.api.Assertions.*;

class SignaturePassiveResolverTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(SignaturePassiveResolverTest.class.getClassLoader());
    private static final SignaturePassiveResolver RESOLVER = new SignaturePassiveResolver(BUNDLE);
    private static final JsonObject CONTENT = document("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json");
    private static final JsonObject GRAPH = document("Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json");

    @Test
    void all28DefinitionsAndRoleChoicesMatchTheRawNormativeCatalog() {
        assertEquals(28, BUNDLE.signaturePassives().forms().size());
        for (var entry : CONTENT.getAsJsonArray("forms")) {
            var row = entry.getAsJsonObject(); var form = ContentId.parse(row.get("formId").getAsString());
            var rawPassive = row.getAsJsonObject("signaturePassive"); var required = id(rawPassive.get("requiredRole").getAsString());
            var definition = BUNDLE.signaturePassives().forms().get(form);
            assertEquals(required, definition.requiredRole());
            assertEquals(rawPassive.get("ru").getAsString(), definition.descriptions().get("ru"));
            assertEquals(rawPassive.get("en").getAsString(), definition.descriptions().get("en"));
            var allowed = new TreeSet<ContentId>(); row.getAsJsonArray("allowedRoles").forEach(role -> allowed.add(id(role.getAsString())));
            assertEquals(allowed, definition.allowedRoles());
            var genome = supportedGenome(form);
            var noRole = RESOLVER.resolve(BUNDLE.hybridGraph().identity(form), genome, Optional.empty());
            assertEquals(Match.NO_ROLE_SELECTED, noRole.match()); assertTrue(noRole.applicablePassive().isEmpty());
            assertEquals(definition, noRole.definition());
            for (var role : BUNDLE.signaturePassives().roles()) {
                var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(form), genome, Optional.of(role));
                assertEquals(role.equals(required) ? Match.ROLE_MATCHED : Match.OTHER_ROLE_SELECTED, result.match());
                assertEquals(role.equals(required) ? Optional.of(definition) : Optional.empty(), result.applicablePassive());
                assertEquals(SignaturePassiveResolver.ALGORITHM, result.algorithm());
                assertEquals(BUNDLE.expressionRules().references(), result.references());
            }
        }
    }

    @Test
    void all2916HeritageAssignmentsSelectTheRawGraphNodeForEveryRole() {
        int count = 0;
        for (var familyEntry : GRAPH.getAsJsonArray("families")) {
            var family = familyEntry.getAsJsonObject(); var familyId = ContentId.parse(family.get("familyId").getAsString());
            var founders = new ArrayList<ContentId>(); family.getAsJsonArray("baseSpecies").forEach(value -> founders.add(ContentId.parse(value.getAsString())));
            var nodes = new HashMap<Set<ContentId>, ContentId>();
            family.getAsJsonArray("forms").forEach(value -> {
                var node = value.getAsJsonObject(); var roots = new TreeSet<ContentId>();
                node.getAsJsonArray("roots").forEach(root -> roots.add(ContentId.parse(root.getAsString())));
                nodes.put(Set.copyOf(roots), ContentId.parse(node.get("id").getAsString()));
            });
            var history = BUNDLE.hybridGraph().identity(nodes.get(Set.copyOf(founders)));
            for (int encoding = 0; encoding < 729; encoding++) {
                int value = encoding; var copies = new ArrayList<ContentId>(); var support = new TreeSet<ContentId>();
                for (int i = 0; i < 6; i++) { var founder = founders.get(value % 3); value /= 3; copies.add(founder); support.add(founder); }
                var genome = genome(BUNDLE.families().get(familyId), copies);
                ContentId expectedForm = nodes.get(support);
                ContentId required = CONTENT.getAsJsonArray("forms").asList().stream().map(row -> row.getAsJsonObject())
                        .filter(row -> row.get("formId").getAsString().equals(expectedForm.toString())).findFirst().orElseThrow()
                        .getAsJsonObject("signaturePassive").get("requiredRole").getAsString().transform(SignaturePassiveResolverTest::id);
                for (var role : BUNDLE.signaturePassives().roles()) {
                    var result = RESOLVER.resolve(history, genome, Optional.of(role));
                    assertEquals(expectedForm, result.definition().formId());
                    assertEquals(role.equals(required), result.applicablePassive().isPresent());
                }
                count++;
            }
        }
        assertEquals(2916, count);
    }

    @Test
    void backcrossLosesHistoricalHybridPassiveAndRoleChangeReevaluatesWithoutMutation() {
        var genome = supportedGenome(id("wolf")); var history = BUNDLE.hybridGraph().identity(id("wolf_fox"));
        var before = new PhenotypeResolver(BUNDLE).resolve(history, genome);
        var scout = RESOLVER.resolve(history, genome, Optional.of(id("scout")));
        assertEquals(id("wolf_fox"), scout.forms().historicalForm().id());
        assertEquals(id("wolf"), scout.definition().formId()); assertTrue(scout.applicablePassive().isEmpty());
        var guard = RESOLVER.resolve(history, genome, Optional.of(id("guard")));
        assertEquals(id("wolf"), guard.applicablePassive().orElseThrow().formId());
        assertEquals(before, new PhenotypeResolver(BUNDLE).resolve(history, genome));
        assertEquals(scout, RESOLVER.resolve(history, genome, Optional.of(id("scout"))));
    }

    @Test
    void aSingleFounderCopyRetainsItsLineageWithoutDominanceOrHistoricalBonusStacking() {
        var schema = BUNDLE.families().get(id("canids"));
        var genome = genome(schema, List.of(id("wolf"), id("wolf"), id("wolf"), id("wolf"), id("wolf"), id("fox")));
        var result = RESOLVER.resolve(BUNDLE.hybridGraph().identity(id("canid_triad")), genome, Optional.of(id("scout")));
        assertEquals(id("wolf_fox"), result.applicablePassive().orElseThrow().formId());
        assertEquals(Map.of(id("wolf"), 5, id("fox"), 1), result.forms().founderCopyCounts());
        assertNotEquals(id("wolf"), result.definition().formId()); assertNotEquals(id("canid_triad"), result.definition().formId());
    }

    @Test
    void noRoleCannotBypassFullGenomePinsOrHistoricalSupportChecks() {
        var original = supportedGenome(id("wolf")); var history = BUNDLE.hybridGraph().identity(id("wolf"));
        var missing = new TreeMap<>(original.genes()); missing.remove(id("element"));
        var wrongVariant = new TreeMap<>(original.genes()); wrongVariant.put(id("element"), new AllelePair(id("unknown"), id("unknown")));
        var stale = new RegistryFingerprint(original.geneSchema().reference(), "0".repeat(64));
        for (var bad : List.of(new Genome(original.geneSchema(), original.familyId(), missing),
                new Genome(original.geneSchema(), original.familyId(), wrongVariant), new Genome(stale, original.familyId(), original.genes()),
                supportedGenome(id("fox")), supportedGenome(id("raven")))) {
            assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(history, bad, Optional.empty()));
        }
        assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(history, original, Optional.of(id("not_registered"))));
    }

    @Test
    void definitionsAreDeeplyImmutableAndRejectInvalidDescriptionsOrRequiredRoles() {
        var original = BUNDLE.signaturePassives().forms().get(id("wolf"));
        var descriptions = new HashMap<>(original.descriptions()); var allowed = new TreeSet<>(original.allowedRoles());
        var copy = new SignaturePassiveCatalog.Definition(original.formId(), original.familyId(), allowed, original.requiredRole(), descriptions);
        descriptions.clear(); allowed.clear(); assertEquals(original, copy);
        assertThrows(UnsupportedOperationException.class, () -> copy.descriptions().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.allowedRoles().clear());
        assertThrows(UnsupportedOperationException.class, () -> BUNDLE.signaturePassives().roles().clear());
        assertThrows(UnsupportedOperationException.class, () -> BUNDLE.signaturePassives().forms().clear());
        for (var text : List.of(Map.of("ru", " ", "en", "test"), Map.of("en", "test"), Map.of("ru", "x", "en", "x", "de", "x"))) {
            assertThrows(IllegalArgumentException.class, () -> new SignaturePassiveCatalog.Definition(original.formId(), original.familyId(), original.allowedRoles(), original.requiredRole(), text));
        }
        assertThrows(IllegalArgumentException.class, () -> new SignaturePassiveCatalog.Definition(original.formId(), original.familyId(), Set.of(id("scout")), original.requiredRole(), original.descriptions()));
    }

    @Test
    void catalogRejectsMissingFormsWrongFamiliesKeysAndStaleDependencies() {
        var original = BUNDLE.signaturePassives(); var definitions = new TreeMap<>(original.forms());
        definitions.remove(id("wolf"));
        assertThrows(IllegalArgumentException.class, () -> new SignaturePassiveCatalog(original.references(), original.roles(), definitions).validate(BUNDLE.hybridGraph()));
        var row = original.forms().get(id("wolf"));
        var foreign = new TreeMap<>(original.forms());
        foreign.put(row.formId(), new SignaturePassiveCatalog.Definition(row.formId(), id("avians"), row.allowedRoles(), row.requiredRole(), row.descriptions()));
        assertThrows(IllegalArgumentException.class, () -> new SignaturePassiveCatalog(original.references(), original.roles(), foreign).validate(BUNDLE.hybridGraph()));
        var wrongKey = Map.of(id("fox"), row);
        assertThrows(IllegalArgumentException.class, () -> new SignaturePassiveCatalog(original.references(), original.roles(), wrongKey));
        var refs = original.references();
        var stale = new ExpressionRules.References(refs.expression(), refs.geneSchema(), refs.hybridGraph(), refs.morphology(), refs.contentCatalogId(), "0".repeat(64));
        var staleCatalog = new SignaturePassiveCatalog(stale, original.roles(), original.forms());
        assertThrows(IllegalArgumentException.class, () -> new CatalogBundle(BUNDLE.defaults(), BUNDLE.families(), BUNDLE.hybridGraph(), BUNDLE.expressionRules(),
                BUNDLE.mutations(), staleCatalog, BUNDLE.contentCatalogId(), BUNDLE.contentCatalogSha256(), BUNDLE.documents()));
    }

    @Test
    void retainedResolverIsDeterministicUnderParallelSelectionAndKeepsItsExactReferences() {
        var identity = BUNDLE.hybridGraph().identity(id("canid_triad")); var genome = supportedGenome(id("wolf_fox"));
        var expected = RESOLVER.resolve(identity, genome, Optional.of(id("scout")));
        java.util.stream.IntStream.range(0, 200).parallel().forEach(i -> assertEquals(expected, RESOLVER.resolve(identity, genome, Optional.of(id("scout")))));
        assertEquals(BUNDLE.contentCatalogSha256(), expected.references().contentCatalogSha256());
        assertEquals(BUNDLE.hybridGraph().reference(), expected.references().hybridGraph());
    }

    private static Genome supportedGenome(ContentId form) {
        var node = BUNDLE.hybridGraph().form(form); var roots = node.roots().stream().sorted().toList();
        var copies = new ArrayList<ContentId>(); for (int i = 0; i < 6; i++) copies.add(roots.get(i % roots.size()));
        return genome(BUNDLE.families().get(node.familyId()), copies);
    }

    private static Genome genome(FamilyGeneSchema schema, List<ContentId> heritage) {
        var pairs = new TreeMap<ContentId, AllelePair>();
        schema.genes().forEach((gene, definition) -> { var variant = definition.variants().stream().sorted().findFirst().orElseThrow(); pairs.put(gene, new AllelePair(variant, variant)); });
        for (int i = 0; i < 3; i++) pairs.put(FamilyGeneSchema.HERITAGE_GENES.get(i), new AllelePair(heritage.get(i * 2), heritage.get(i * 2 + 1)));
        return schema.createGenome(pairs);
    }

    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }
    private static JsonObject document(String name) {
        return JsonParser.parseString(new String(BUNDLE.documents().get(name).copy(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
