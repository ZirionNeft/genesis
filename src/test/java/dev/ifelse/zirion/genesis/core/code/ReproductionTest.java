package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.*;
import dev.ifelse.zirion.genesis.core.morphology.MorphologyRules;
import dev.ifelse.zirion.genesis.core.phenotype.Stat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

import static dev.ifelse.zirion.genesis.core.code.CodeFixtures.*;
import static dev.ifelse.zirion.genesis.core.code.ReproductionResolver.*;
import static org.junit.jupiter.api.Assertions.*;

class ReproductionTest {
    private static final ExtensionRegistry NONE = ExtensionRegistry.builder().freeze();

    private static GenesisBlueprint changed(GenesisBlueprint source, Genome genome, RulesetVersion family, RulesetVersion rig,
                                            RulesetVersion archetype, RulesetVersion assets) {
        return new GenesisBlueprint(genome, family, rig, archetype, assets, source.cosmeticSeed(), source.extensions());
    }

    private static void rejects(ReproductionResolver resolver, GenesisBlueprint source, Mode mode, Reason reason) {
        var result = assertInstanceOf(Rejected.class, resolver.resolve(source, mode, Operation.SPAWN));
        assertEquals(reason, result.reason());
    }

    @Test
    void exactResolutionProducesTheRegisteredPhenotypeAndAppearanceWithoutInstanceCreation() {
        var context = context(1, Set.of());
        var source = GenesisCodeCodec.decode(GenesisCodeCodec.encode(context.blueprint()));
        var result = assertInstanceOf(Resolved.class, context.resolver(NONE).resolve(source, Mode.EXACT, Operation.SPAWN));
        assertSame(source, result.source());
        assertSame(source.genome(), result.evaluationGenome());
        assertFalse(result.definitionsChanged());
        assertEquals(50.0, result.phenotype().potential().values().get(Stat.HEALTH));
        assertEquals(source.rig().id(), result.morphology().appearance().rigId());
        assertEquals(Long.MIN_VALUE, result.source().cosmeticSeed());
    }

    @Test
    void geneticCurrentModeExplicitlyReevaluatesAllelesWithoutRewritingTheOriginalCode() {
        var old = context(1, Set.of());
        var current = context(2, Set.of());
        String stored = GenesisCodeCodec.encode(old.blueprint());
        rejects(current.resolver(NONE), old.blueprint(), Mode.EXACT, Reason.VERSION_UNSUPPORTED);
        var result = assertInstanceOf(Resolved.class, current.resolver(NONE).resolve(old.blueprint(), Mode.GENETIC_CURRENT, Operation.IMPORT));
        assertEquals(old.blueprint().genome().loci(), result.evaluationGenome().loci());
        assertEquals(current.blueprint().genome().ruleset(), result.evaluationGenome().ruleset());
        assertTrue(result.definitionsChanged());
        assertEquals(60.0, result.phenotype().potential().values().get(Stat.HEALTH));
        assertEquals(current.profile().assetSet(), result.profile().assetSet());
        assertEquals(stored, GenesisCodeCodec.encode(result.source()));
        assertInstanceOf(Resolved.class, old.resolver(NONE).resolve(old.blueprint(), Mode.EXACT, Operation.IMPORT));
    }

    @Test
    void everyExactVersionReferenceIsCheckedIncludingAssets() {
        var context = context(1, Set.of());
        var source = context.blueprint();
        List<GenesisBlueprint> variants = List.of(
                changed(source, new Genome(new RulesetVersion(source.genome().ruleset().id(), 2), source.genome().loci()), source.family(), source.rig(), source.archetype(), source.assetSet()),
                changed(source, source.genome(), new RulesetVersion(source.family().id(), 2), source.rig(), source.archetype(), source.assetSet()),
                changed(source, source.genome(), source.family(), new RulesetVersion(source.rig().id(), 2), source.archetype(), source.assetSet()),
                changed(source, source.genome(), source.family(), source.rig(), new RulesetVersion(source.archetype().id(), 2), source.assetSet()),
                changed(source, source.genome(), source.family(), source.rig(), source.archetype(), ref("fixture:missing_assets", 1)));
        for (var variant : variants) rejects(context.resolver(NONE), variant, Mode.EXACT, Reason.VERSION_UNSUPPORTED);
    }

    @Test
    void neitherModeRenamesIdsSubstitutesUnknownAllelesOrChangesFamilyAndRig() {
        var context = context(1, Set.of());
        var source = context.blueprint();
        for (Mode mode : Mode.values()) {
            rejects(context.resolver(NONE), changed(source, source.genome(), ref("other:family", 1), source.rig(), source.archetype(), source.assetSet()), mode, Reason.FAMILY_MISMATCH);
            rejects(context.resolver(NONE), changed(source, source.genome(), source.family(), ref("other:rig", 1), source.archetype(), source.assetSet()), mode, Reason.RIG_MISMATCH);
            rejects(context.resolver(NONE), changed(source, source.genome(), source.family(), source.rig(), ref("other:archetype", 1), source.assetSet()), mode, Reason.UNKNOWN_ARCHETYPE);
            rejects(context.resolver(NONE), changed(source, new Genome(ref("other:rules", 1), source.genome().loci()), source.family(), source.rig(), source.archetype(), source.assetSet()), mode, Reason.RULESET_MISMATCH);
            var pairs = new EnumMap<>(source.genome().loci());
            pairs.put(Locus.HEAD, new AllelePair(ContentId.parse("absent:head"), ContentId.parse("absent:head")));
            var unknown = changed(source, new Genome(source.genome().ruleset(), pairs), source.family(), source.rig(), source.archetype(), source.assetSet());
            String stored = GenesisCodeCodec.encode(unknown);
            rejects(context.resolver(NONE), GenesisCodeCodec.decode(stored), mode, Reason.INVALID_GENOME);
            assertEquals(stored, GenesisCodeCodec.encode(unknown));
        }
    }

    @Test
    void missingExtensionBlocksEveryDependentOperationAndRestoringItPreservesTheCode() {
        var data = vector().extensions().getFirst();
        var context = context(1, Set.of(data.key()));
        var source = withExtensions(context.blueprint(), List.of(data));
        String stored = GenesisCodeCodec.encode(source);
        for (Operation operation : Operation.values()) {
            var result = assertInstanceOf(Rejected.class, context.resolver(NONE).resolve(source, Mode.EXACT, operation));
            assertEquals(operation, result.operation());
            assertEquals(Reason.EXTENSION_UNRESOLVED, result.reason());
            assertEquals(ExtensionRegistry.Reason.MISSING_EXTENSION, result.extensionIssue().orElseThrow().reason());
        }
        var restored = ExtensionRegistry.builder().register("v", new ExtensionRegistry.Support(data.key(), data.kind(), data.payload()::equals)).freeze();
        var result = assertInstanceOf(Resolved.class, context.resolver(restored).resolve(GenesisCodeCodec.decode(stored), Mode.EXACT, Operation.SPAWN));
        assertEquals(stored, GenesisCodeCodec.encode(result.source()));
        var dropped = assertInstanceOf(Rejected.class, context.resolver(restored).resolve(context.blueprint(), Mode.EXACT, Operation.BREED));
        assertEquals(ExtensionRegistry.Reason.MISSING_REQUIRED_DATA, dropped.extensionIssue().orElseThrow().reason());
    }

    @Test
    void aChecksumDoesNotClaimProvenanceOrBypassRequiredDependencies() {
        var data = vector().extensions().getFirst();
        var context = context(1, Set.of(data.key()));
        // An attacker can recompute a valid checksum after dropping data: resolution still rejects.
        var modified = GenesisCodeCodec.decode(GenesisCodeCodec.encode(context.blueprint()));
        rejects(context.resolver(NONE), modified, Mode.EXACT, Reason.EXTENSION_UNRESOLVED);
    }

    @Test
    void aMissingExtensionRejectsOnlyItsDependentArchetypeWhileTheBaseCycleRemainsReady() {
        var base = context(1, Set.of());
        var data = vector().extensions().getFirst();
        var profile = base.profile();
        var extended = new ReproductionProfile(ref("fixture:extended_archetype", 1), profile.family(), profile.rig(),
                profile.assetSet(), profile.phenotype(), profile.morphology(), profile.availableAppearances(), Set.of(data.key()));
        var resolver = new ReproductionResolver(base.catalog(), List.of(profile, extended), NONE);
        var source = base.blueprint();
        var dependent = new GenesisBlueprint(source.genome(), source.family(), source.rig(), extended.reference(),
                source.assetSet(), source.cosmeticSeed(), List.of(data));
        rejects(resolver, dependent, Mode.EXACT, Reason.EXTENSION_UNRESOLVED);
        assertInstanceOf(Resolved.class, resolver.resolve(source, Mode.EXACT, Operation.BREED));
    }

    @Test
    void profilesRejectIncompleteVisualCoverageMissingPlansAndConflictingDependencies() {
        var context = context(1, Set.of());
        var profile = context.profile();
        assertThrows(IllegalArgumentException.class, () -> new ReproductionProfile(profile.reference(), profile.family(), profile.rig(), profile.assetSet(),
                profile.phenotype(), profile.morphology(), Set.of(), Set.of()));
        var genetics = context.catalog().rulesets().values().iterator().next();
        var incomplete = new MorphologyRules(ref("fixture:incomplete", 1), genetics, profile.rig().id(), Set.of(Locus.HEAD), java.util.Map.of());
        assertThrows(IllegalArgumentException.class, () -> new ReproductionProfile(profile.reference(), profile.family(), profile.rig(), profile.assetSet(),
                profile.phenotype(), incomplete, profile.availableAppearances(), Set.of()));
        var data = vector().extensions().getFirst();
        var conflicting = Set.of(data.key(), new ExtensionPayload.Key(ref("v:x", 999), 1));
        assertThrows(IllegalArgumentException.class, () -> new ReproductionProfile(profile.reference(), profile.family(), profile.rig(), profile.assetSet(),
                profile.phenotype(), profile.morphology(), profile.availableAppearances(), conflicting));
        assertThrows(IllegalArgumentException.class, () -> new ReproductionResolver(context.catalog(), List.of(profile, profile), NONE));
    }

    @Test
    void mismatchedFrozenCatalogIsRejectedAndContextCopiesItsProfiles() {
        var context = context(1, Set.of());
        var newer = context(2, Set.of());
        assertThrows(IllegalArgumentException.class, () -> new ReproductionResolver(newer.catalog(), List.of(context.profile()), NONE));
        var profiles = new ArrayList<>(List.of(context.profile()));
        var resolver = new ReproductionResolver(context.catalog(), profiles, NONE);
        profiles.clear();
        assertInstanceOf(Resolved.class, resolver.resolve(context.blueprint(), Mode.EXACT, Operation.SPAWN));
    }
}
