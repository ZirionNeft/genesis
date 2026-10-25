package dev.ifelse.zirion.genesis.core.wild;

import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;

import java.util.Objects;

/** Retained inputs for first observation/replay. A later reload cannot change an existing candidate. */
public record WildGenerationContext(long configRevision, ServerConfig.WildArcana arcana,
                                    CatalogBundle catalogs, WildDistributionPolicy policy) {
    public WildGenerationContext {
        if (configRevision < 0) throw new IllegalArgumentException("Negative config revision");
        Objects.requireNonNull(arcana); Objects.requireNonNull(catalogs); Objects.requireNonNull(policy);
        if (!arcana.distributionVersion().equals(WildGenomeGenerator.ARCANA_DISTRIBUTION)) {
            throw new IllegalArgumentException("Unsupported wild ARCANA distribution version");
        }
        policy.validate(catalogs);
    }
}
