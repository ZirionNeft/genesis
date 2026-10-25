package dev.ifelse.zirion.genesis.prototype.compatibility;

/** Closed technical fixtures, never an allowlist for gameplay adoption. */
public enum RoleProbeProfile {
    FOX("fox", "Fox", "Genesis_CompatibilityFox", "genesisroleprobe", "genesis.compatibilityEntityId"),
    WOLF_BLACK("wolf_black", "Wolf_Black", "Genesis_CompatibilityWolfBlack", "genesiswolfblackprobe", "genesis.compatibilityWolfBlackEntityId"),
    WOLF_WHITE("wolf_white", "Wolf_White", "Genesis_CompatibilityWolfWhite", "genesiswolfwhiteprobe", "genesis.compatibilityWolfWhiteEntityId");

    private final String key, sourceRole, probeRole, commandName, entityProperty;

    RoleProbeProfile(String key, String sourceRole, String probeRole, String commandName, String entityProperty) {
        this.key = key;
        this.sourceRole = sourceRole;
        this.probeRole = probeRole;
        this.commandName = commandName;
        this.entityProperty = entityProperty;
    }

    public String key() { return key; }
    public String sourceRole() { return sourceRole; }
    public String probeRole() { return probeRole; }
    public String modelId() { return sourceRole; }
    public String commandName() { return commandName; }
    public String entityProperty() { return entityProperty; }
    public boolean acceptsRole(String role) { return sourceRole.equals(role) || probeRole.equals(role); }

    public static RoleProbeProfile fromKey(String key) {
        for (var profile : values()) if (profile.key.equals(key)) return profile;
        throw new IllegalArgumentException("Unknown diagnostic fixture profile");
    }

    public static RoleProbeProfile fromSourceRole(String role) {
        for (var profile : values()) if (profile.sourceRole.equals(role)) return profile;
        throw new IllegalArgumentException("Unknown diagnostic source role");
    }
}
