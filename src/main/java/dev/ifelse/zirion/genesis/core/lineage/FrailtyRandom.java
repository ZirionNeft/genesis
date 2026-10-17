package dev.ifelse.zirion.genesis.core.lineage;

/** Separate birth-condition stream; never changes inheritance, de novo selection or attempt success. */
@FunctionalInterface
public interface FrailtyRandom {
    double nextUnit();
}
