package dev.ifelse.zirion.genesis.core.genetics;

import java.util.Objects;

/** Canonical unordered diploid pair; parental provenance belongs outside this value. */
public record AllelePair(ContentId first, ContentId second) {
    public AllelePair {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if (first.compareTo(second) > 0) {
            ContentId swap = first;
            first = second;
            second = swap;
        }
    }
}
