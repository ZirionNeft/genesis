package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Mutable in-memory copies of the normative sources for rejection tests; never writes specs/. */
final class CatalogFixtures {
    private CatalogFixtures() {}

    static Map<CatalogDocument, byte[]> originals() throws Exception {
        var result = new EnumMap<CatalogDocument, byte[]>(CatalogDocument.class);
        Path root = Path.of(System.getProperty("genesis.gddDirectory"));
        for (CatalogDocument document : CatalogDocument.values()) result.put(document, Files.readAllBytes(root.resolve(document.filename())));
        return result;
    }

    static Map<CatalogDocument, JsonObject> coherent() throws Exception {
        var result = new EnumMap<CatalogDocument, JsonObject>(CatalogDocument.class);
        originals().forEach((key, value) -> result.put(key, JsonParser.parseString(new String(value, StandardCharsets.UTF_8)).getAsJsonObject()));
        return result;
    }

    static Map<CatalogDocument, byte[]> bytes(Map<CatalogDocument, JsonObject> documents) {
        var result = new EnumMap<CatalogDocument, byte[]>(CatalogDocument.class);
        documents.forEach((key, value) -> result.put(key, value.toString().getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    static void refresh(Map<CatalogDocument, JsonObject> documents) {
        JsonObject config = documents.get(CatalogDocument.CONFIG).getAsJsonObject("genetics").getAsJsonObject("registries");
        JsonObject compatibility = documents.get(CatalogDocument.EXPRESSION).getAsJsonObject("catalogCompatibility");
        compatibility.addProperty("contentCatalogSha256", hash(CatalogDocument.CONTENT, documents));
        compatibility.addProperty("hybridGraphSha256", hash(CatalogDocument.GRAPH, documents));
        for (var pair : Map.of("geneSchema", CatalogDocument.SCHEMA, "hybridGraph", CatalogDocument.GRAPH,
                "expressionRules", CatalogDocument.EXPRESSION, "morphologySet", CatalogDocument.MORPHOLOGY).entrySet()) {
            config.getAsJsonObject(pair.getKey()).addProperty("sha256", hash(pair.getValue(), documents));
        }
    }

    private static String hash(CatalogDocument key, Map<CatalogDocument, JsonObject> documents) {
        return DesignFingerprint.sha256(new JsonNode(documents.get(key), key.filename() + "#"));
    }
}
