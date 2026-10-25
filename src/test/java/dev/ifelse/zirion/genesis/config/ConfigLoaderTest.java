package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {
    static JsonObject defaultsJson() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of(System.getProperty("genesis.gddDirectory"))
                .resolve(CatalogDocument.CONFIG.filename()), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static ByteArrayInputStream stream(String json) { return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)); }
    static ServerConfig load(JsonObject json) { return new ConfigLoader().load(stream(json.toString()), "test-config"); }
    static JsonObject at(JsonObject root, String... path) { for (String key : path) root = root.getAsJsonObject(key); return root; }

    @Test
    void parsesAllRealSettingsAndNullableQuotaWithoutJavaDefaults() throws Exception {
        var config = load(defaultsJson());
        assertEquals(ServerConfig.Provider.AUTO, config.storage().provider());
        assertEquals("genesis:state", config.storage().nativeStorage().resourceId().toString());
        assertEquals(ServerConfig.Visibility.AFTER_ANALYSIS, config.genesisCode().visibility());
        assertEquals(ServerConfig.DeathPolicy.INSTANT, config.companionLifecycle().deathPolicy());
        assertNull(config.companionLifecycle().maxRevives());
        assertEquals(16, config.lifeSparks().initialCradleCapacity());
        assertEquals(new BigDecimal("0.22"), config.genetics().mutation().probabilityByTier().mastery().magical());
        assertFalse(config.companionLifecycle().summon().allowInCombat());
        assertEquals(10, config.companionLifecycle().summon().combatExitGraceSeconds());
        assertEquals(4, config.experience().noveltyMultipliers().size());
        assertThrows(UnsupportedOperationException.class, () -> config.experience().noveltyMultipliers().clear());
    }

    @Test
    void acceptsZeroAndPositiveReviveQuotasButRejectsNegative() throws Exception {
        var json = defaultsJson();
        for (int quota : List.of(0, 3)) {
            at(json, "companionLifecycle").addProperty("maxRevives", quota);
            assertEquals(quota, load(json).companionLifecycle().maxRevives());
        }
        at(json, "companionLifecycle").addProperty("maxRevives", -1);
        assertThrows(ConfigurationException.class, () -> load(json));
    }

    @Test
    void acceptsAllDeclaredProvidersAndDeathPolicies() throws Exception {
        var json = defaultsJson();
        for (String provider : List.of("auto", "hytale_native", "sqlite", "postgresql")) {
            at(json, "storage").addProperty("provider", provider);
            assertEquals(provider, load(json).storage().provider().name().toLowerCase(java.util.Locale.ROOT));
        }
        for (String policy : List.of("instant", "revival_station", "permadeath")) {
            at(json, "companionLifecycle").addProperty("deathPolicy", policy);
            assertEquals(policy, load(json).companionLifecycle().deathPolicy().name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void missingFieldsNeverTurnIntoPrimitiveDefaults() throws Exception {
        var json = defaultsJson();
        at(json, "companionLifecycle", "summon").remove("allowInCombat");
        var failure = assertThrows(ConfigurationException.class, () -> load(json));
        assertEquals("MISSING_FIELD", failure.code());
        assertTrue(failure.path().endsWith("/companionLifecycle/summon/allowInCombat"));
    }

    @Test
    void unknownLegacyAndMisspelledFieldsAreRejected() throws Exception {
        for (String field : List.of("frailty", "lifeCharges", "growht")) {
            var json = defaultsJson(); json.addProperty(field, true);
            assertEquals("UNKNOWN_FIELD", assertThrows(ConfigurationException.class, () -> load(json)).code());
        }
    }

    @Test
    void noBooleanStringOrFractionalIntegerCoercion() throws Exception {
        for (String value : List.of("\"10\"", "true", "1.5", "null", "2147483648")) {
            var json = defaultsJson(); at(json, "companionLifecycle", "summon").add("combatExitGraceSeconds", JsonParser.parseString(value));
            assertThrows(ConfigurationException.class, () -> load(json), value);
        }
        var json = defaultsJson(); at(json, "companionLifecycle", "summon").addProperty("allowInCombat", "false");
        assertThrows(ConfigurationException.class, () -> load(json));
    }

    @Test
    void everyNonQuotaNullIsRejected() throws Exception {
        var json = defaultsJson(); at(json, "storage").add("provider", com.google.gson.JsonNull.INSTANCE);
        assertThrows(ConfigurationException.class, () -> load(json));
    }

    @Test
    void rejectsInvalidEnumsWithoutEchoingInput() throws Exception {
        var json = defaultsJson(); at(json, "storage").addProperty("provider", "secret-password-value");
        var failure = assertThrows(ConfigurationException.class, () -> load(json));
        assertEquals("ENUM", failure.code()); assertFalse(failure.getMessage().contains("secret-password-value"));
    }

    @Test
    void enforcesExclusiveMutationProbabilitiesAndEnvironmentBounds() throws Exception {
        var json = defaultsJson(); at(json, "genetics", "mutation", "probabilityByTier", "mastery").addProperty("magical", 0.99);
        assertThrows(ConfigurationException.class, () -> load(json));
        var invalid = defaultsJson(); at(invalid, "breeding").addProperty("environmentMinMultiplier", 1.2);
        assertThrows(ConfigurationException.class, () -> load(invalid));
        var badChance = defaultsJson(); at(badChance, "breeding").addProperty("maxSuccessProbability", 1.1);
        assertThrows(ConfigurationException.class, () -> load(badChance));
    }

    @Test
    void validatesTimeoutsCooldownsAndFearThresholds() throws Exception {
        for (String field : List.of("baseCooldownMinutes", "maxActiveSessionMinutes")) {
            var json = defaultsJson(); at(json, "breeding").addProperty(field, 1);
            assertThrows(ConfigurationException.class, () -> load(json));
        }
        var json = defaultsJson(); at(json, "taming").addProperty("criticalFearThreshold", 100);
        assertThrows(ConfigurationException.class, () -> load(json));
    }

    @Test
    void disabledManualModeNeedsAutoOnlyWhenBreedingIsEnabled() throws Exception {
        var json = defaultsJson(); at(json, "breeding").addProperty("manualMinigameEnabled", false);
        assertFalse(load(json).breeding().manualMinigameEnabled());
        at(json, "breeding").addProperty("autoBreedingEnabled", false);
        assertThrows(ConfigurationException.class, () -> load(json));
        at(json, "breeding").addProperty("enabled", false);
        assertFalse(load(json).breeding().enabled());
    }

    @Test
    void rejectsUnsafePersistencePolicyAndNonlocalSqliteFile() throws Exception {
        for (String[] field : List.of(new String[]{"failIfUnavailable"}, new String[]{"backup", "requiredBeforeMigration"},
                new String[]{"native", "verifyDurability"})) {
            var json = defaultsJson(); var storage = at(json, "storage");
            if (field.length == 1) storage.addProperty(field[0], false);
            else at(storage, field[0]).addProperty(field[1], false);
            assertThrows(ConfigurationException.class, () -> load(json));
        }
        var json = defaultsJson(); at(json, "storage", "sqlite").addProperty("file", "../other.db");
        assertThrows(ConfigurationException.class, () -> load(json));
    }

    @Test
    void validatesCrossSectionRoleAndVeteranLimits() throws Exception {
        var json = defaultsJson(); at(json, "balance").addProperty("learnedRoleAbilitySlots", 3);
        assertThrows(ConfigurationException.class, () -> load(json));
        var veteran = defaultsJson(); at(veteran, "progression").addProperty("veteranMinLevel", 31);
        assertThrows(ConfigurationException.class, () -> load(veteran));
    }

    @Test
    void parsedSnapshotsDoNotChangeWhenTheSourceTreeChanges() throws Exception {
        var json = defaultsJson(); var snapshot = load(json);
        at(json, "breeding").addProperty("baseCooldownMinutes", 99);
        assertEquals(10, snapshot.breeding().baseCooldownMinutes());
    }

    @Test
    void parserRejectsDuplicateKeysAndNonstandardJson() throws Exception {
        var failure = assertThrows(ConfigurationException.class,
                () -> new ConfigLoader().load(stream("{\"storage\":{},\"storage\":{}}"), "duplicate"));
        assertEquals("DUPLICATE", failure.code());
        for (String json : List.of("{'x':1}", "{\"x\":NaN}", "{\"x\":1e999}", "{\"x\":1,}", "{} {}", "/* comment */{}")) {
            assertThrows(ConfigurationException.class, () -> new ConfigLoader().load(stream(json), "invalid"));
        }
    }

    @Test
    void parserRejectsBadUnicodeAndExcessiveNesting() {
        assertThrows(ConfigurationException.class, () -> StrictJson.parse(new byte[]{(byte) 0xff}, "utf8"));
        assertThrows(ConfigurationException.class, () -> StrictJson.parse("{\"x\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8), "surrogate"));
        assertThrows(ConfigurationException.class, () -> StrictJson.parse(("[".repeat(70) + "0" + "]".repeat(70)).getBytes(StandardCharsets.UTF_8), "depth"));
        assertThrows(ConfigurationException.class, () -> StrictJson.parse(new byte[StrictJson.MAX_BYTES + 1], "size"));
    }
}
