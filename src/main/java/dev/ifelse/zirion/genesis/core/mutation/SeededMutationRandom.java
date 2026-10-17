package dev.ifelse.zirion.genesis.core.mutation;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.Random;

/** Plain Java Random nextDouble/nextInt; 48-bit state seeded by the versioned generation-seed scheme. */
public final class SeededMutationRandom implements MutationRandom {
    public static final ContentId ALGORITHM = ContentId.parse("genesis:mutation_java_random_lcg48_v1");
    private final Random random;

    public SeededMutationRandom(long seed) {
        random = new Random(seed);
    }

    @Override
    public double nextUnit() { return random.nextDouble(); }

    @Override
    public int nextIndex(int bound) { return random.nextInt(bound); }
}
