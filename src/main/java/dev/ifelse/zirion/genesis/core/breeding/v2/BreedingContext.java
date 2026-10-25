package dev.ifelse.zirion.genesis.core.breeding.v2;

import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;

import java.util.Objects;

/** Retained operation snapshot. Revision is diagnostic, never a substitute for retaining the values. */
public record BreedingContext(long configRevision, ServerConfig config, CatalogBundle catalogs) {
    public BreedingContext {
        if (configRevision < 0) throw new IllegalArgumentException("Negative config revision");
        Objects.requireNonNull(config); Objects.requireNonNull(catalogs);
        if (!config.genetics().registries().equals(catalogs.defaults().genetics().registries())
                || config.balance().geneticStatMultiplierMin().compareTo(catalogs.defaults().balance().geneticStatMultiplierMin()) != 0
                || config.balance().geneticStatMultiplierMax().compareTo(catalogs.defaults().balance().geneticStatMultiplierMax()) != 0) {
            throw new IllegalArgumentException("Attempt config requires different genetic definitions");
        }
    }
}
