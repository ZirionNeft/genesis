package dev.ifelse.zirion.genesis.core.content.v2;

import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.phenotype.v2.ExpressionRules;
import dev.ifelse.zirion.genesis.core.mutation.v2.MutationCatalog;
import dev.ifelse.zirion.genesis.core.role.SignaturePassiveCatalog;

import java.util.Map;
import java.util.Objects;

/** Frozen executable genetic definitions; morphology assets still require separate bindings and acceptance. */
public record CatalogBundle(ServerConfig defaults, Map<ContentId, FamilyGeneSchema> families, HybridGraph hybridGraph,
                            ExpressionRules expressionRules, MutationCatalog mutations, SignaturePassiveCatalog signaturePassives,
                            ContentId contentCatalogId, String contentCatalogSha256,
                            Map<String, ImmutableBytes> documents) {
    public CatalogBundle {
        Objects.requireNonNull(defaults); Objects.requireNonNull(contentCatalogId); Objects.requireNonNull(contentCatalogSha256);
        families = Map.copyOf(families);
        documents = Map.copyOf(documents);
        Objects.requireNonNull(hybridGraph);
        if (!hybridGraph.reference().equals(defaults.genetics().registries().hybridGraph().fingerprint())) {
            throw new IllegalArgumentException("Graph differs from the pinned registry");
        }
        for (var schema : families.values()) {
            if (!schema.reference().equals(defaults.genetics().registries().geneSchema().fingerprint())) {
                throw new IllegalArgumentException("Family schema differs from the pinned registry");
            }
        }
        new FormResolver(hybridGraph, families); // validate graph/schema compatibility before publishing the bundle
        Objects.requireNonNull(expressionRules);
        var refs = expressionRules.references();
        if (!refs.expression().equals(defaults.genetics().registries().expressionRules().fingerprint())
                || !refs.morphology().equals(defaults.genetics().registries().morphologySet().fingerprint())
                || !refs.contentCatalogId().equals(contentCatalogId) || !refs.contentCatalogSha256().equals(contentCatalogSha256)) {
            throw new IllegalArgumentException("Expression differs from the pinned registries/content");
        }
        expressionRules.validate(hybridGraph, families);
        Objects.requireNonNull(mutations);
        if (!mutations.contentId().equals(contentCatalogId) || !mutations.contentSha256().equals(contentCatalogSha256)) {
            throw new IllegalArgumentException("Mutation catalog differs from the pinned content");
        }
        mutations.validate(families);
        Objects.requireNonNull(signaturePassives);
        if (!signaturePassives.references().equals(expressionRules.references())) {
            throw new IllegalArgumentException("Passive catalog differs from the pinned registries/content");
        }
        signaturePassives.validate(hybridGraph);
    }
}
