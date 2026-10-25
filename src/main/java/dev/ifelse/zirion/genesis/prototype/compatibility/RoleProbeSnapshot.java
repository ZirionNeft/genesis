package dev.ifelse.zirion.genesis.prototype.compatibility;

import org.bson.BsonDocument;
import org.bson.BsonString;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Bounded comparison evidence, never a deserializer that overwrites a live NPC. */
public final class RoleProbeSnapshot {
    static final int MAX_BYTES = 131_072;
    private final BsonDocument baseline;

    public RoleProbeSnapshot(UUID entityId, String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Missing or oversized diagnostic snapshot");
        }
        baseline = BsonDocument.parse(json);
        var components = baseline.getDocument("Components");
        if (components.containsKey(RoleProbeMarker.ID)
                || !entityId.equals(components.getDocument("UUID").getBinary("UUID").asUuid())) {
            throw new IllegalArgumentException("Snapshot identity mismatch or recursive marker");
        }
    }

    public Set<String> differences(BsonDocument current) {
        return differences(current, baseline);
    }

    /** Compare a role checkpoint while allowing exactly the expected RoleName change. */
    public Set<String> differencesInRole(BsonDocument current, String expectedRole) {
        if (!profile().acceptsRole(expectedRole)) {
            throw new IllegalArgumentException("Unsupported diagnostic role");
        }
        var expected = baseline.clone();
        expected.getDocument("Components").getDocument("NPC").put("RoleName", new BsonString(expectedRole));
        return differences(current, expected);
    }

    public RoleProbeProfile profile() {
        return RoleProbeProfile.fromSourceRole(baseline.getDocument("Components").getDocument("NPC").getString("RoleName").getValue());
    }

    private static Set<String> differences(BsonDocument current, BsonDocument baseline) {
        var copy = current.clone();
        copy.getDocument("Components").remove(RoleProbeMarker.ID);
        var before = baseline.getDocument("Components");
        var after = copy.getDocument("Components");
        var changed = new TreeSet<String>();
        var keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (var key : keys) {
            if (!java.util.Objects.equals(before.get(key), after.get(key))) changed.add(key);
        }
        if (!baseline.keySet().equals(copy.keySet())) changed.add("<root-keys>");
        for (var key : baseline.keySet()) {
            if (!key.equals("Components") && !java.util.Objects.equals(baseline.get(key), copy.get(key))) {
                changed.add("<root:" + key + ">");
            }
        }
        return Set.copyOf(changed);
    }
}
