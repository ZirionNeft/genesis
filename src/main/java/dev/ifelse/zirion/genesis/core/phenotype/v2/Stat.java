package dev.ifelse.zirion.genesis.core.phenotype.v2;

/** The seven v0.7 physiological values, independent of the historical ten-stat prototype. */
public enum Stat {
    HP("hp"), ATTACK("attack"), ARMOR("armor"), SPEED_BPS("speedBps"),
    STAMINA("stamina"), CARGO_SLOTS("cargoSlots"), SENSE_RADIUS("senseRadius");

    private final String key;
    Stat(String key) { this.key = key; }
    public String key() { return key; }

    public static Stat fromKey(String key) {
        for (Stat stat : values()) if (stat.key.equals(key)) return stat;
        throw new IllegalArgumentException("Unknown v0.7 stat: " + key);
    }
}
