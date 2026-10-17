package dev.ifelse.zirion.genesis.core.lineage;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Random;

/** Dedicated Java Random nextDouble with 48-bit state, seeded by the generation-seed workflow. */
public final class SeededFrailtyRandom implements FrailtyRandom {
    public static final ContentId ALGORITHM = ContentId.parse("genesis:frailty_java_random_lcg48_v1");
    private final Random random;

    public SeededFrailtyRandom(long seed) { random = new Random(seed); }

    @Override
    public double nextUnit() { return random.nextDouble(); }
}
