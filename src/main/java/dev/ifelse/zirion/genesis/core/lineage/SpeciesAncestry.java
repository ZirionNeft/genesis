package dev.ifelse.zirion.genesis.core.lineage;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Source-species fractions, including explicitly unknown ancestry. Never a stat bonus or genotype. */
public record SpeciesAncestry(Map<ContentId, BigDecimal> shares, BigDecimal unknownShare) {
    public SpeciesAncestry {
        Objects.requireNonNull(shares, "shares");
        Objects.requireNonNull(unknownShare, "unknownShare");
        TreeMap<ContentId, BigDecimal> copy = new TreeMap<>();
        BigDecimal total = unknownShare;
        requireShare(unknownShare);
        for (var entry : shares.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "species ID");
            requireShare(entry.getValue());
            total = total.add(entry.getValue());
            if (entry.getValue().signum() != 0) copy.put(entry.getKey(), entry.getValue().stripTrailingZeros());
        }
        if (total.compareTo(BigDecimal.ONE) != 0) throw new IllegalArgumentException("Ancestry shares must sum to 1");
        shares = Collections.unmodifiableMap(copy);
        unknownShare = unknownShare.stripTrailingZeros();
    }

    public static SpeciesAncestry original(ContentId species) {
        return new SpeciesAncestry(Map.of(species, BigDecimal.ONE), BigDecimal.ZERO);
    }

    public static SpeciesAncestry unknown() {
        return new SpeciesAncestry(Map.of(), BigDecimal.ONE);
    }

    public static SpeciesAncestry childOf(SpeciesAncestry a, SpeciesAncestry b) {
        Objects.requireNonNull(a, "parent A ancestry");
        Objects.requireNonNull(b, "parent B ancestry");
        TreeMap<ContentId, BigDecimal> result = new TreeMap<>();
        a.shares.forEach((id, share) -> result.merge(id, share.divide(BigDecimal.TWO), BigDecimal::add));
        b.shares.forEach((id, share) -> result.merge(id, share.divide(BigDecimal.TWO), BigDecimal::add));
        return new SpeciesAncestry(result, a.unknownShare.add(b.unknownShare).divide(BigDecimal.TWO));
    }

    private static void requireShare(BigDecimal share) {
        Objects.requireNonNull(share, "ancestry share");
        if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Ancestry share must be in [0,1]");
        }
    }
}
