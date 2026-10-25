package dev.ifelse.zirion.genesis.config;

import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static dev.ifelse.zirion.genesis.config.StrictJson.error;

/** Config-only reload, after a validated bundle. Retained snapshots remain valid for in-flight operations. */
public final class ConfigurationRuntime {
    public record Snapshot(long revision, ServerConfig config, CatalogBundle catalogs) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("Negative revision");
            Objects.requireNonNull(config); Objects.requireNonNull(catalogs);
            verifyPinnedDefinitions(config, catalogs.defaults());
        }
    }

    private final CatalogBundle catalogs;
    private final AtomicReference<Snapshot> active;

    public ConfigurationRuntime(CatalogBundle catalogs, ServerConfig initialConfig) {
        this.catalogs = Objects.requireNonNull(catalogs);
        active = new AtomicReference<>(new Snapshot(0, initialConfig, catalogs));
    }

    public Snapshot snapshot() { return active.get(); }

    /**
     * Reads off the world/tick thread, then validates before the single publication point.
     * Failure leaves the old snapshot intact. No listeners or game side effects run in the CAS loop.
     */
    public Snapshot reload(InputStream source, String name) {
        ServerConfig candidate = new ConfigLoader().load(source, name);
        verifyPinnedDefinitions(candidate, catalogs.defaults());
        return active.updateAndGet(previous -> {
            if (!candidate.storage().equals(previous.config().storage())) {
                throw error("STORAGE_RELOAD_DENIED", "config#/storage", "Storage changes require explicit provider lifecycle/migration handling");
            }
            return new Snapshot(Math.incrementExact(previous.revision()), candidate, catalogs);
        });
    }

    private static void verifyPinnedDefinitions(ServerConfig candidate, ServerConfig defaults) {
        if (!candidate.genetics().registries().equals(defaults.genetics().registries())) {
            throw error("REGISTRY_RELOAD_DENIED", "config#/genetics/registries", "Registry changes require an explicit migration");
        }
        if (candidate.balance().geneticStatMultiplierMin().compareTo(defaults.balance().geneticStatMultiplierMin()) != 0
                || candidate.balance().geneticStatMultiplierMax().compareTo(defaults.balance().geneticStatMultiplierMax()) != 0) {
            throw error("GENETIC_RULE_CHANGE", "config#/balance", "Genetic formula bounds belong to the pinned expression rules");
        }
        if (!candidate.lifeSparks().noveltyResearchCredit().equals(defaults.lifeSparks().noveltyResearchCredit())) {
            throw error("POLICY_CHANGE_DENIED", "config#/lifeSparks/noveltyResearchCredit", "Unimplemented policy text cannot replace the catalog's novelty rule");
        }
    }
}
