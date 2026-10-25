package dev.ifelse.zirion.genesis.prototype;

import com.hypixel.hytale.codec.ExtraInfo;
import dev.ifelse.zirion.genesis.prototype.compatibility.*;
import org.bson.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RoleProbeProfilesTest {
    private static final UUID ENTITY = UUID.fromString("8fcf2266-b692-439e-b8d6-dce712bf16ed");

    @Test
    void everyProfileRetainsItsExactCodecSnapshotAndRejectsOtherProfilesRoles() {
        for (var profile : RoleProbeProfile.values()) {
            var baseline = baseline(profile);
            var marker = new RoleProbeMarker(UUID.randomUUID(), UUID.randomUUID(), ENTITY, baseline.toJson());
            var decoded = RoleProbeMarker.CODEC.decode(RoleProbeMarker.CODEC.encode(marker, new ExtraInfo()), new ExtraInfo());
            assertEquals(marker.snapshotJson(), decoded.snapshotJson());
            assertEquals(profile, decoded.baseline().profile());
            var changed = baseline.clone();
            changed.getDocument("Components").getDocument("NPC").put("RoleName", new BsonString(profile.probeRole()));
            assertTrue(decoded.baseline().differencesInRole(changed, profile.probeRole()).isEmpty());
            assertEquals(java.util.Set.of("NPC"), decoded.baseline().differences(changed));
            for (var other : RoleProbeProfile.values()) if (profile != other) {
                assertThrows(IllegalArgumentException.class, () -> decoded.baseline().differencesInRole(changed, other.sourceRole()));
                assertThrows(IllegalArgumentException.class, () -> decoded.baseline().differencesInRole(changed, other.probeRole()));
            }
            changed.getDocument("Components").getDocument("other:opaque").put("Payload", new BsonInt32(2));
            assertEquals(java.util.Set.of("other:opaque"), decoded.baseline().differencesInRole(changed, profile.probeRole()));
            assertTrue(decoded.baseline().differences(baseline).isEmpty());
        }
    }

    @Test
    void unknownOrAliasBaselinesCannotAuthorizeSourceRoleTransitions() {
        assertThrows(IllegalArgumentException.class, () -> RoleProbeProfile.fromKey("wolf"));
        assertThrows(IllegalArgumentException.class, () -> RoleProbeProfile.fromSourceRole("Wolf_Black_Tamed"));
        var doc = baseline(RoleProbeProfile.WOLF_BLACK);
        doc.getDocument("Components").getDocument("NPC").put("RoleName", new BsonString("Genesis_CompatibilityWolfBlack"));
        assertThrows(IllegalArgumentException.class, () -> new RoleProbeSnapshot(ENTITY, doc.toJson()).profile());
    }

    @Test
    void allTargetsAreValidatedBeforeRegistrationAndCannotShareAnEntity() {
        var properties = new HashMap<String, String>();
        properties.put(RoleProbeProfile.FOX.entityProperty(), ENTITY.toString());
        var targets = RoleProbeSetup.readTargets(properties::get);
        assertEquals(Map.of(RoleProbeProfile.FOX, ENTITY), targets);
        assertThrows(UnsupportedOperationException.class, () -> targets.clear());
        properties.put(RoleProbeProfile.WOLF_BLACK.entityProperty(), ENTITY.toString());
        assertThrows(IllegalArgumentException.class, () -> RoleProbeSetup.readTargets(properties::get));
        properties.put(RoleProbeProfile.WOLF_BLACK.entityProperty(), "invalid");
        assertThrows(IllegalArgumentException.class, () -> RoleProbeSetup.readTargets(properties::get));
        properties.put(RoleProbeProfile.WOLF_BLACK.entityProperty(), UUID.randomUUID().toString());
        assertEquals(2, RoleProbeSetup.readTargets(properties::get).size());
        assertTrue(RoleProbeSetup.readTargets(key -> "").isEmpty());
    }

    @Test
    void packagedAliasesInheritOnlyTheirExactNativeSourceWithoutModifications() throws Exception {
        for (var profile : RoleProbeProfile.values()) {
            try (var stream = getClass().getResourceAsStream("/Server/NPC/Roles/Genesis/" + profile.probeRole() + ".json")) {
                assertNotNull(stream);
                var json = BsonDocument.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("Variant", json.getString("Type").getValue());
                assertEquals(profile.sourceRole(), json.getString("Reference").getValue());
                assertTrue(json.getDocument("Modify").isEmpty());
            }
        }
    }

    private static BsonDocument baseline(RoleProbeProfile profile) {
        return new BsonDocument("Components", new BsonDocument("UUID", new BsonDocument("UUID", new BsonBinary(ENTITY)))
                .append("NPC", new BsonDocument("RoleName", new BsonString(profile.sourceRole())))
                .append("other:opaque", new BsonDocument("Payload", new BsonInt32(1))));
    }
}
