package dev.ifelse.zirion.genesis.core.breeding;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Random;

/** Dedicated inheritance stream, using Java's specified portable Random/nextBoolean algorithm. */
public final class SeededAlleleRandom implements AlleleRandom {
    public static final ContentId ALGORITHM = ContentId.parse("genesis:java_random_lcg48_v1");
    private final Random random;

    public SeededAlleleRandom(long seed) {
        random = new Random(seed);
    }

    @Override
    public boolean chooseSecond() {
        return random.nextBoolean();
    }
}
