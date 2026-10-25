package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static dev.ifelse.zirion.genesis.config.ConfigLoaderTest.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationRuntimeTest {
    private ConfigurationRuntime runtime() throws Exception {
        var bundle = new CatalogLoader().load(CatalogFixtures.bytes(CatalogFixtures.coherent()));
        return new ConfigurationRuntime(bundle, bundle.defaults());
    }

    @Test
    void reloadPublishesOneNewSnapshotAndRetainsTheOldOperationSnapshot() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        // Retain the actual bundle pins in the full operator configuration.
        JsonObject candidate = configJson(old);
        at(candidate, "breeding").addProperty("baseCooldownMinutes", 12);
        var current = runtime.reload(stream(candidate.toString()), "reload");
        assertEquals(1, current.revision()); assertSame(current, runtime.snapshot());
        assertEquals(12, current.config().breeding().baseCooldownMinutes());
        assertEquals(10, old.config().breeding().baseCooldownMinutes());
        assertSame(old.catalogs(), current.catalogs()); assertEquals(0, old.revision());
    }

    private JsonObject configJson(ConfigurationRuntime.Snapshot snapshot) throws Exception {
        var json = defaultsJson(); var pins = at(json, "genetics", "registries");
        var current = snapshot.catalogs().defaults().genetics().registries();
        pins.getAsJsonObject("expressionRules").addProperty("sha256", current.expressionRules().sha256());
        return json;
    }

    @Test
    void invalidReloadAndRegistryReplacementLeaveThePreviousSnapshotActive() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        var invalid = configJson(old); at(invalid, "growth").addProperty("infantDurationMinutes", -1);
        assertThrows(ConfigurationException.class, () -> runtime.reload(stream(invalid.toString()), "invalid"));
        assertSame(old, runtime.snapshot());
        var pins = configJson(old); at(pins, "genetics", "registries", "geneSchema").addProperty("sha256", "f".repeat(64));
        assertEquals("REGISTRY_RELOAD_DENIED", assertThrows(ConfigurationException.class,
                () -> runtime.reload(stream(pins.toString()), "pins")).code());
        assertSame(old, runtime.snapshot());
    }

    @Test
    void genericDifficultyReloadCannotRedefineGeneticPotential() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        var candidate = configJson(old); at(candidate, "balance").addProperty("geneticStatMultiplierMax", 1.5);
        assertEquals("GENETIC_RULE_CHANGE", assertThrows(ConfigurationException.class,
                () -> runtime.reload(stream(candidate.toString()), "rules")).code());
        assertSame(old, runtime.snapshot());
    }

    @Test
    void futureAttemptPoliciesMayChangeWithoutMutatingThePinnedCatalogOrPriorPolicies() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        var candidate = configJson(old); at(candidate, "genetics", "mutation", "probabilityByTier", "base").addProperty("magical", 0.08);
        at(candidate, "companionLifecycle").addProperty("deathPolicy", "revival_station");
        at(candidate, "balance").addProperty("maxInnateActiveSpells", 0);
        var next = runtime.reload(stream(candidate.toString()), "policy");
        assertEquals(new java.math.BigDecimal("0.08"), next.config().genetics().mutation().probabilityByTier().base().magical());
        assertEquals(new java.math.BigDecimal("0.06"), old.config().genetics().mutation().probabilityByTier().base().magical());
        assertEquals(old.catalogs().defaults(), next.catalogs().defaults());
        assertEquals(0, next.config().balance().maxInnateActiveSpells());
        assertEquals(1, old.config().balance().maxInnateActiveSpells());
    }

    @Test
    void descriptiveNoveltyPolicyCannotSilentlyRedefineResearchRewards() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        var candidate = configJson(old); at(candidate, "lifeSparks").addProperty("noveltyResearchCredit", "every duplicate");
        assertEquals("POLICY_CHANGE_DENIED", assertThrows(ConfigurationException.class,
                () -> runtime.reload(stream(candidate.toString()), "novelty")).code());
        assertSame(old, runtime.snapshot());
    }

    @Test
    void configReloadCannotSwitchProviderOrRedirectItsDatabase() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        for (String target : new String[]{"provider", "file", "connectionSecretRef"}) {
            var candidate = configJson(old);
            if (target.equals("provider")) at(candidate, "storage").addProperty(target, "postgresql");
            if (target.equals("file")) at(candidate, "storage", "sqlite").addProperty(target, "other.db");
            if (target.equals("connectionSecretRef")) at(candidate, "storage", "postgresql").addProperty(target, "OTHER_DATABASE_URL");
            assertEquals("STORAGE_RELOAD_DENIED", assertThrows(ConfigurationException.class,
                    () -> runtime.reload(stream(candidate.toString()), "storage")).code());
            assertSame(old, runtime.snapshot());
        }
    }

    @Test
    void concurrentReloadsHaveUniqueMonotonicRevisionsAndNoPartialPublication() throws Exception {
        var runtime = runtime(); var old = runtime.snapshot();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<Future<ConfigurationRuntime.Snapshot>>();
            for (int i = 0; i < 8; i++) {
                var candidate = configJson(old); at(candidate, "breeding").addProperty("baseCooldownMinutes", 11 + i);
                futures.add(executor.submit(() -> runtime.reload(stream(candidate.toString()), "concurrent")));
            }
            var revisions = new HashSet<Long>();
            for (Future<ConfigurationRuntime.Snapshot> future : futures) revisions.add(future.get().revision());
            assertEquals(8, revisions.size()); assertEquals(8, runtime.snapshot().revision());
            assertEquals(10, old.config().breeding().baseCooldownMinutes());
        }
    }
}
