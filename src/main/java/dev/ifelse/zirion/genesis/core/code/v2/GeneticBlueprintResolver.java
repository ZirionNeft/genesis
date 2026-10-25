package dev.ifelse.zirion.genesis.core.code.v2;

import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;

import java.util.Objects;

/** Exact genetic definitions only; successful resolution grants no import/spawn or asset-availability permission. */
public final class GeneticBlueprintResolver {
    private final CatalogBundle catalogs;
    public GeneticBlueprintResolver(CatalogBundle catalogs) { this.catalogs = Objects.requireNonNull(catalogs); }

    public PhenotypeResolver.Result resolve(GenesisBlueprint blueprint) {
        Objects.requireNonNull(blueprint);
        if (!catalogs.expressionRules().references().equals(blueprint.references())) throw new IllegalArgumentException("Unavailable exact GEN1 definitions");
        // Unknown opaque data survives transport, but dependent operations require trusted v2 extension support.
        if (!blueprint.extensions().isEmpty()) throw new IllegalArgumentException("GEN1 extension definitions are unresolved");
        return new PhenotypeResolver(catalogs).resolve(blueprint.form(), blueprint.genome());
    }
}
