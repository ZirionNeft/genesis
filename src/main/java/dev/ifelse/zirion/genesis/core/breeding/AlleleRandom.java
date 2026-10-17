package dev.ifelse.zirion.genesis.core.breeding;

/** One unbiased choice between the two canonical allele copies; false means first, true second. */
@FunctionalInterface
public interface AlleleRandom {
    boolean chooseSecond();
}
