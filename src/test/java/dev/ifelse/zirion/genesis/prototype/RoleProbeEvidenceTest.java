package dev.ifelse.zirion.genesis.prototype;

import com.hypixel.hytale.codec.ExtraInfo;
import dev.ifelse.zirion.genesis.prototype.compatibility.RoleProbeMarker;
import dev.ifelse.zirion.genesis.prototype.compatibility.RoleProbeSnapshot;
import org.bson.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Checks evidence retention and fail-closed comparison, not live role/AI restoration. */
class RoleProbeEvidenceTest {
    private static final UUID ENTITY = UUID.fromString("4e7ad1a7-1360-40e3-bcc1-987256431b18");
    private static final UUID WORLD = UUID.fromString("fa1572fa-af13-4b67-a216-9110a93eb7b2");

    @Test
    void hytaleRegistrationDefaultsAreValidButCannotAuthorizeAnOperation() {
        assertDoesNotThrow(() -> RoleProbeMarker.CODEC.validateDefaults(new ExtraInfo(), new java.util.HashSet<>()));
        var empty = RoleProbeMarker.CODEC.decode(new BsonDocument(), new ExtraInfo());
        assertFalse(empty.isArmed());
        assertFalse(empty.matches(WORLD, ENTITY));
        assertFalse(empty.clone().isArmed());
        assertThrows(IllegalStateException.class, empty::operationId);
        assertThrows(IllegalStateException.class, empty::baseline);
    }

    @Test
    void codecAndClonePreserveTheExactSnapshotAndOperationScope() {
        String snapshot = baseline().toJson();
        var marker = new RoleProbeMarker(UUID.randomUUID(), WORLD, ENTITY, snapshot);
        var decoded = RoleProbeMarker.CODEC.decode(RoleProbeMarker.CODEC.encode(marker, new ExtraInfo()), new ExtraInfo());
        assertEquals(snapshot, decoded.snapshotJson());
        assertEquals(marker.operationId(), decoded.operationId());
        assertTrue(decoded.matches(WORLD, ENTITY));
        assertFalse(decoded.matches(UUID.randomUUID(), ENTITY));
        assertFalse(decoded.matches(WORLD, UUID.randomUUID()));
        var clone = decoded.clone();
        assertNotSame(decoded, clone);
        assertEquals(snapshot, clone.snapshotJson());
        assertEquals(decoded.operationId(), clone.operationId());
    }

    @Test
    void comparisonIgnoresOnlyOurMarkerAndDoesNotMutateTheCurrentSnapshot() {
        var current = baseline();
        current.getDocument("Components").put(RoleProbeMarker.ID, new BsonDocument("Token", new BsonString("evidence")));
        var original = current.clone();
        assertTrue(new RoleProbeSnapshot(ENTITY, baseline().toJson()).differences(current).isEmpty());
        assertEquals(original, current);
        assertTrue(current.getDocument("Components").containsKey(RoleProbeMarker.ID));
    }

    @Test
    void roleCheckpointAllowsOnlyTheDeclaredRoleNameAndKeepsBothDocumentsUnchanged() {
        String original = baseline().toJson();
        var snapshot = new RoleProbeSnapshot(ENTITY, original);
        var current = baseline();
        current.getDocument("Components").getDocument("NPC")
                .put("RoleName", new BsonString("Genesis_CompatibilityFox"));
        var unchanged = current.clone();
        assertTrue(snapshot.differencesInRole(current, "Genesis_CompatibilityFox").isEmpty());
        assertEquals(unchanged, current);
        assertEquals(java.util.Set.of("NPC"), snapshot.differences(current));
        current.getDocument("Components").getDocument("NPC").put("OtherField", BsonBoolean.TRUE);
        assertEquals(java.util.Set.of("NPC"), snapshot.differencesInRole(current, "Genesis_CompatibilityFox"));
        assertThrows(IllegalArgumentException.class, () -> snapshot.differencesInRole(current, "Wolf"));
        assertTrue(snapshot.differences(baseline()).isEmpty());
        assertEquals(original, baseline().toJson());
    }

    @Test
    void changesOrLossOfForeignOpaqueDataPreventSuccessfulFinish() {
        var snapshot = new RoleProbeSnapshot(ENTITY, baseline().toJson());
        var changed = baseline();
        changed.getDocument("Components").getDocument("other:opaque").put("Bytes", new BsonBinary(new byte[]{0, 1, -1}));
        assertEquals(java.util.Set.of("other:opaque"), snapshot.differences(changed));
        changed = baseline();
        changed.getDocument("Components").remove("other:opaque");
        assertEquals(java.util.Set.of("other:opaque"), snapshot.differences(changed));
        changed = baseline();
        changed.put("FutureRoot", new BsonBoolean(true));
        assertFalse(snapshot.differences(changed).isEmpty());
    }

    @Test
    void crossEntityRecursiveMissingAndOversizedSnapshotsAreRejected() {
        assertThrows(RuntimeException.class, () -> new RoleProbeSnapshot(UUID.randomUUID(), baseline().toJson()));
        assertThrows(RuntimeException.class, () -> new RoleProbeSnapshot(ENTITY, "{}"));
        assertThrows(RuntimeException.class, () -> new RoleProbeSnapshot(ENTITY, " ".repeat(131_073)));
        var recursive = baseline();
        recursive.getDocument("Components").put(RoleProbeMarker.ID, new BsonDocument());
        assertThrows(RuntimeException.class, () -> new RoleProbeSnapshot(ENTITY, recursive.toJson()));
    }

    @Test
    void unknownMarkerSchemaAndMissingIdentityAreRejectedByTheRealCodec() {
        var marker = new RoleProbeMarker(UUID.randomUUID(), WORLD, ENTITY, baseline().toJson());
        var encoded = RoleProbeMarker.CODEC.encode(marker, new ExtraInfo());
        encoded.put("Schema", new BsonInt32(2));
        assertThrows(RuntimeException.class, () -> RoleProbeMarker.CODEC.decode(encoded, new ExtraInfo()));
        var missing = RoleProbeMarker.CODEC.encode(marker, new ExtraInfo());
        missing.remove("World");
        assertThrows(RuntimeException.class, () -> RoleProbeMarker.CODEC.decode(missing, new ExtraInfo()));
    }

    private static BsonDocument baseline() {
        return new BsonDocument("Components", new BsonDocument("UUID", new BsonDocument("UUID", new BsonBinary(ENTITY)))
                .append("NPC", new BsonDocument("RoleName", new BsonString("Fox")))
                .append("other:opaque", new BsonDocument("Bytes", new BsonBinary(new byte[]{0, -1, 1}))))
                .append("Version", new BsonInt32(1));
    }
}
