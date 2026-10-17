package dev.ifelse.zirion.genesis.core.breeding;

/** Initial GDD values, replaceable by validated server configuration later. No breed bonus yet. */
public record CompatibilityPolicy(double sameOriginalSpeciesChance, double sameFamilyChance) {
    public CompatibilityPolicy {
        for (double value : new double[]{sameOriginalSpeciesChance, sameFamilyChance}) {
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Compatibility chance must be in [0, 1]");
            }
        }
    }

    public static CompatibilityPolicy gddDefaults() {
        return new CompatibilityPolicy(0.90, 0.70);
    }
}
