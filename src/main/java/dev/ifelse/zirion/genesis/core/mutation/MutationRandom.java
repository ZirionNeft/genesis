package dev.ifelse.zirion.genesis.core.mutation;

/** Dedicated stream: it must not be shared with inheritance, attempt success, cosmetics or UI. */
public interface MutationRandom {
    double nextUnit();

    int nextIndex(int bound);
}
