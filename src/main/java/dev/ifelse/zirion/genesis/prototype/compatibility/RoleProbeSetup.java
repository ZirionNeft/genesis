package dev.ifelse.zirion.genesis.prototype.compatibility;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;

import java.util.UUID;
import java.util.Map;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.function.Function;

public final class RoleProbeSetup {
    private RoleProbeSetup() { }

    public static void install(JavaPlugin plugin) {
        // Keep the codec available even with actions disabled so a saved diagnostic component remains readable.
        var type = plugin.getEntityStoreRegistry().registerComponent(RoleProbeMarker.class, RoleProbeMarker.ID, RoleProbeMarker.CODEC);
        if (!Boolean.getBoolean("genesis.compatibilityRoleProbe")) return;
        try {
            var world = UUID.fromString(System.getProperty("genesis.compatibilityWorldId", ""));
            var targets = readTargets(key -> System.getProperty(key, ""));
            boolean createFixtures = Boolean.getBoolean("genesis.compatibilityCreateFixtures");
            if (targets.isEmpty() && !createFixtures) throw new IllegalArgumentException("No diagnostic targets");
            for (var entry : targets.entrySet()) {
                plugin.getCommandRegistry().registerCommand(new RoleProbeCommand(type, plugin.getLogger(), world, entry.getValue(), entry.getKey()));
                plugin.getLogger().atInfo().log("Genesis role probe enabled for explicit fixture profile=%s world=%s entity=%s",
                        entry.getKey().key(), world, entry.getValue());
            }
            if (createFixtures) plugin.getCommandRegistry().registerCommand(new RoleFixtureCommand(plugin.getLogger(), world, targets.keySet()));
        } catch (IllegalArgumentException invalidTarget) {
            plugin.getLogger().atWarning().log("Genesis role probe disabled: valid explicit world/unique fixture UUIDs required");
        }
    }

    /** Validate every configured target before registering any mutating command. */
    public static Map<RoleProbeProfile, UUID> readTargets(Function<String, String> properties) {
        var targets = new EnumMap<RoleProbeProfile, UUID>(RoleProbeProfile.class);
        var identities = new HashSet<UUID>();
        for (var profile : RoleProbeProfile.values()) {
            String value = properties.apply(profile.entityProperty());
            if (value == null || value.isEmpty()) continue;
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value) || !identities.add(id)) throw new IllegalArgumentException("Invalid or duplicate fixture UUID");
            targets.put(profile, id);
        }
        return Map.copyOf(targets);
    }
}
