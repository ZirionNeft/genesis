package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonArray;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import org.junit.jupiter.api.Test;

import static dev.ifelse.zirion.genesis.config.CatalogDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class SignaturePassiveLoaderTest {
    @Test
    void unsupportedSelectionPolicyRejectsEvenWhenFingerprintsMatch() throws Exception {
        var json = CatalogFixtures.coherent();
        json.get(EXPRESSION).getAsJsonObject("catalogCompatibility").addProperty("signaturePassives", "select from historical form");
        CatalogFixtures.refresh(json);
        var failure = assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json)));
        assertEquals(EXPRESSION.filename() + "#/catalogCompatibility/signaturePassives", failure.path());
    }

    @Test
    void duplicateAllowedRoleAndUnavailableRequiredRoleAreRejected() throws Exception {
        var duplicate = CatalogFixtures.coherent();
        duplicate.get(CONTENT).getAsJsonArray("forms").get(0).getAsJsonObject().getAsJsonArray("allowedRoles").add("guard");
        CatalogFixtures.refresh(duplicate);
        assertEquals("DUPLICATE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(duplicate))).code());
        var invalid = CatalogFixtures.coherent();
        invalid.get(CONTENT).getAsJsonArray("forms").get(0).getAsJsonObject().getAsJsonObject("signaturePassive").addProperty("requiredRole", "support");
        CatalogFixtures.refresh(invalid);
        assertEquals("ROLE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(invalid))).code());
    }

    @Test
    void missingBlankAndNonStringDescriptionsRejectWithNoPartialBundle() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            var json = CatalogFixtures.coherent();
            var passive = json.get(CONTENT).getAsJsonArray("forms").get(0).getAsJsonObject().getAsJsonObject("signaturePassive");
            if (mode == 0) passive.remove("ru"); else if (mode == 1) passive.addProperty("en", "  "); else passive.add("ru", new JsonArray());
            CatalogFixtures.refresh(json);
            assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json)));
        }
    }

    @Test
    void additionalEffectFieldsAndUnknownRoleSetsCannotBeSilentlyIgnored() throws Exception {
        var extra = CatalogFixtures.coherent();
        extra.get(CONTENT).getAsJsonArray("forms").get(0).getAsJsonObject().getAsJsonObject("signaturePassive").addProperty("damageMultiplier", .5);
        CatalogFixtures.refresh(extra);
        assertEquals("PASSIVE_FIELDS", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(extra))).code());
        var roles = CatalogFixtures.coherent(); var definitions = roles.get(CONTENT).getAsJsonObject("roles");
        var support = definitions.remove("support"); definitions.add("unknown", support);
        CatalogFixtures.refresh(roles);
        assertEquals("ROLES", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(roles))).code());
    }

    @Test
    void changedLocalizedDescriptionsStayOpaqueAndDoNotAffectSelectionOrDefinitionsOfOtherSnapshots() throws Exception {
        var initial = new CatalogLoader().loadBundled(getClass().getClassLoader());
        var json = CatalogFixtures.coherent();
        var passive = json.get(CONTENT).getAsJsonArray("forms").get(0).getAsJsonObject().getAsJsonObject("signaturePassive");
        passive.addProperty("ru", "Отображаемое описание без чисел"); passive.addProperty("en", "Display text with no numeric rule");
        CatalogFixtures.refresh(json);
        var changed = new CatalogLoader().load(CatalogFixtures.bytes(json));
        var wolf = ContentId.parse("genesis:wolf");
        assertEquals(initial.signaturePassives().forms().get(wolf).requiredRole(), changed.signaturePassives().forms().get(wolf).requiredRole());
        assertNotEquals(initial.contentCatalogSha256(), changed.contentCatalogSha256());
        assertNotEquals(initial.signaturePassives().forms().get(wolf).descriptions(), changed.signaturePassives().forms().get(wolf).descriptions());
        passive.addProperty("ru", "changed again after publication");
        assertEquals("Отображаемое описание без чисел", changed.signaturePassives().forms().get(wolf).descriptions().get("ru"));
        assertEquals(initial.expressionRules().references(), initial.signaturePassives().references());
        assertEquals(changed.expressionRules().references(), changed.signaturePassives().references());
    }
}
