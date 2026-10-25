package dev.ifelse.zirion.genesis.core.config;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Immutable v0.7 settings; values come from JSON, never a second Java defaults table. */
public record ServerConfig(Code genesisCode, Storage storage, Taming taming, Breeding breeding,
                           LifeSparks lifeSparks, Growth growth, Nursery nursery, Experience experience,
                           Progression progression, CompanionLifecycle companionLifecycle,
                           Genetics genetics, Guide guide, Balance balance) {
    public ServerConfig {
        Objects.requireNonNull(genesisCode); Objects.requireNonNull(storage); Objects.requireNonNull(taming);
        Objects.requireNonNull(breeding); Objects.requireNonNull(lifeSparks); Objects.requireNonNull(growth);
        Objects.requireNonNull(nursery); Objects.requireNonNull(experience); Objects.requireNonNull(progression);
        Objects.requireNonNull(companionLifecycle); Objects.requireNonNull(genetics); Objects.requireNonNull(guide);
        Objects.requireNonNull(balance);
        require(progression.roleSkillSlots() == balance.learnedRoleAbilitySlots(), "Role slot counts differ");
        require(!progression.veteranEnabled() || progression.veteranMinLevel() <= experience.maxLevel(),
                "Veteran level exceeds max level");
    }

    public enum Visibility { ALWAYS, AFTER_ANALYSIS }
    public enum ImportMode { EXACT }
    public enum MissingExtensions { REJECT }
    public enum Provider { AUTO, HYTALE_NATIVE, SQLITE, POSTGRESQL }
    public enum Consistency { STRONG }
    public enum JournalMode { WAL }
    public enum Synchronous { FULL }
    public enum UnknownTraits { KEEP }
    public enum OfflineStages { ALL }
    public enum DeathPolicy { INSTANT, REVIVAL_STATION, PERMADEATH }
    public enum FinalState { FINAL_DEAD }
    public enum InventoryOnFatal { RETAIN }
    public enum OwnerDeathBehavior { DISMISS_ALIVE }
    public enum RevivalTargets { BOTH }

    public record Code(Visibility visibility, boolean ownerExportEnabled, boolean publicPassportEnabled,
                       boolean playerSpawnFromCodeEnabled, boolean adminSpawnEnabled, ImportMode importMode,
                       MissingExtensions missingExtensions, CodeLimits limits) {
        public Code { Objects.requireNonNull(visibility); Objects.requireNonNull(importMode);
            Objects.requireNonNull(missingExtensions); Objects.requireNonNull(limits); }
    }

    public record CodeLimits(int maxCodeChars, int maxPayloadBytes, int maxGenes, int maxExtensions) {
        public CodeLimits { positive(maxCodeChars, "maxCodeChars"); positive(maxPayloadBytes, "maxPayloadBytes");
            positive(maxGenes, "maxGenes"); nonnegative(maxExtensions, "maxExtensions"); }
    }

    public record Storage(Provider provider, Consistency requiredConsistency, boolean failIfUnavailable,
                          boolean allowExperimental, NativeStorage nativeStorage, Sqlite sqlite,
                          Postgresql postgresql, Backup backup, Migration migration) {
        public Storage { Objects.requireNonNull(provider); Objects.requireNonNull(requiredConsistency);
            Objects.requireNonNull(nativeStorage); Objects.requireNonNull(sqlite); Objects.requireNonNull(postgresql);
            Objects.requireNonNull(backup); Objects.requireNonNull(migration);
            require(failIfUnavailable, "Unavailable storage must fail closed"); }
    }

    public record NativeStorage(ContentId resourceId, boolean verifyDurability) {
        public NativeStorage { Objects.requireNonNull(resourceId); require(verifyDurability, "Native durability must be verified"); }
    }

    public record Sqlite(String file, JournalMode journalMode, Synchronous synchronous) {
        public Sqlite { text(file, "SQLite file"); Objects.requireNonNull(journalMode); Objects.requireNonNull(synchronous);
            // A single data-directory filename, not an arbitrary absolute or traversing path.
            require(file.matches("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*") && !file.equals(".."), "Invalid SQLite filename"); }
    }

    public record Postgresql(String connectionSecretRef, String schema) {
        public Postgresql { text(connectionSecretRef, "connectionSecretRef"); text(schema, "PostgreSQL schema");
            require(connectionSecretRef.matches("[A-Z_][A-Z0-9_]*"), "Invalid connection secret reference");
            require(schema.matches("[a-z_][a-z0-9_]*"), "Invalid PostgreSQL schema identifier"); }
    }

    public record Backup(boolean requiredBeforeMigration) {
        public Backup { require(requiredBeforeMigration, "Provider migration requires backup"); }
    }

    public record Migration(boolean autoMigrateBetweenProviders) {
        public Migration { require(!autoMigrateBetweenProviders, "Provider migration must be explicit"); }
    }

    public record Taming(boolean enabled, boolean minigameEnabled, int initialTrust, int initialFear,
                         int trustThreshold, int criticalFearThreshold, int failureFearThreshold,
                         int maxContactRounds, BigDecimal retainedTrustOnInterrupt, boolean quickTamingEnabled,
                         int quickTamingUnlocksAfterSpeciesTamings, boolean reactionTimerEnabled) {
        public Taming { score(initialTrust, "initialTrust"); score(initialFear, "initialFear"); score(trustThreshold, "trustThreshold");
            score(criticalFearThreshold, "criticalFearThreshold"); score(failureFearThreshold, "failureFearThreshold");
            positive(trustThreshold, "trustThreshold"); positive(maxContactRounds, "maxContactRounds");
            positive(quickTamingUnlocksAfterSpeciesTamings, "quickTamingUnlocksAfterSpeciesTamings");
            fraction(retainedTrustOnInterrupt, "retainedTrustOnInterrupt");
            require(initialTrust < trustThreshold, "Initial trust must be below taming threshold");
            require(initialFear < criticalFearThreshold && criticalFearThreshold < failureFearThreshold, "Fear thresholds are out of order"); }
    }

    public record Breeding(boolean enabled, boolean manualMinigameEnabled, boolean autoBreedingEnabled,
                           int autoUnlockAfterFamilySuccesses, BigDecimal baseSameFormSuccess,
                           BigDecimal baseSameFamilySuccess, BigDecimal maxSuccessProbability,
                           BigDecimal environmentMinMultiplier, BigDecimal environmentMaxMultiplier,
                           int manualScoreBonusThreshold, BigDecimal manualCooldownReduction,
                           int baseCooldownMinutes, int minCooldownMinutes, int disconnectGraceSeconds,
                           int maxActiveSessionMinutes, int sparksPerSuccess, boolean startRequiresReservedSparkSlot) {
        public Breeding { positive(autoUnlockAfterFamilySuccesses, "autoUnlockAfterFamilySuccesses");
            fraction(baseSameFormSuccess, "baseSameFormSuccess"); fraction(baseSameFamilySuccess, "baseSameFamilySuccess");
            fraction(maxSuccessProbability, "maxSuccessProbability"); positive(environmentMinMultiplier, "environmentMinMultiplier");
            positive(environmentMaxMultiplier, "environmentMaxMultiplier"); score(manualScoreBonusThreshold, "manualScoreBonusThreshold");
            fraction(manualCooldownReduction, "manualCooldownReduction"); positive(baseCooldownMinutes, "baseCooldownMinutes");
            positive(minCooldownMinutes, "minCooldownMinutes"); nonnegative(disconnectGraceSeconds, "disconnectGraceSeconds");
            positive(maxActiveSessionMinutes, "maxActiveSessionMinutes");
            require(!enabled || manualMinigameEnabled || autoBreedingEnabled, "Enabled breeding needs a usable mode");
            require(environmentMinMultiplier.compareTo(environmentMaxMultiplier) <= 0, "Environment bounds are reversed");
            require(minCooldownMinutes <= baseCooldownMinutes, "Minimum cooldown exceeds base cooldown");
            require(disconnectGraceSeconds <= 60L * maxActiveSessionMinutes, "Disconnect grace exceeds session lifetime");
            require(sparksPerSuccess == 1 && startRequiresReservedSparkSlot, "Breeding requires one Spark and a reserved slot"); }
    }

    public record LifeSparks(int initialCradleCapacity, int upgradedCradleCapacity, BigDecimal dispersalRefundRate,
                             boolean keepUnknownByDefault, boolean autoDispersalEnabled,
                             UnknownTraits unknownTraitsDefault, String noveltyResearchCredit) {
        public LifeSparks { positive(initialCradleCapacity, "initialCradleCapacity"); positive(upgradedCradleCapacity, "upgradedCradleCapacity");
            fraction(dispersalRefundRate, "dispersalRefundRate"); Objects.requireNonNull(unknownTraitsDefault);
            text(noveltyResearchCredit, "noveltyResearchCredit");
            require(upgradedCradleCapacity >= initialCradleCapacity, "Cradle upgrade cannot reduce capacity");
            require(dispersalRefundRate.signum() == 0, "Dispersal has no material refund");
            require(keepUnknownByDefault, "Unknown traits must be kept by default"); }
    }

    public record Growth(boolean enabled, int infantDurationMinutes, int juvenileDurationMinutes,
                         BigDecimal maxGrowthSpeedBonus, BigDecimal welfareSpeedFloor, boolean allowOfflineGrowth,
                         int maxOfflineProgressMinutes, OfflineStages offlineStages, boolean allowNeglectDeath) {
        public Growth { positive(infantDurationMinutes, "infantDurationMinutes"); positive(juvenileDurationMinutes, "juvenileDurationMinutes");
            fraction(maxGrowthSpeedBonus, "maxGrowthSpeedBonus"); fraction(welfareSpeedFloor, "welfareSpeedFloor");
            positive(welfareSpeedFloor, "welfareSpeedFloor"); nonnegative(maxOfflineProgressMinutes, "maxOfflineProgressMinutes");
            Objects.requireNonNull(offlineStages); }
    }

    public record Nursery(boolean careMinigameEnabled, boolean uniqueCareMilestones, boolean allowAutomaticCare) {}

    public record Experience(boolean enabled, int maxLevel, BigDecimal xpMultiplier, int repetitiveActionWindowMinutes,
                             List<BigDecimal> noveltyMultipliers, boolean allowAutomaticTraining) {
        public Experience { positive(maxLevel, "maxLevel"); nonnegative(xpMultiplier, "xpMultiplier");
            positive(repetitiveActionWindowMinutes, "repetitiveActionWindowMinutes");
            noveltyMultipliers = List.copyOf(noveltyMultipliers);
            require(!noveltyMultipliers.isEmpty(), "Novelty multipliers must not be empty");
            BigDecimal previous = BigDecimal.ONE;
            for (BigDecimal multiplier : noveltyMultipliers) {
                fraction(multiplier, "novelty multiplier");
                require(multiplier.compareTo(previous) <= 0, "Novelty multipliers must not increase"); previous = multiplier;
            }
            require(noveltyMultipliers.getFirst().compareTo(BigDecimal.ONE) == 0
                    && noveltyMultipliers.getLast().signum() == 0, "Novelty starts at one and ends at zero"); }
    }

    public record Progression(boolean allowRetraining, int roleSkillSlots, boolean veteranEnabled,
                              int veteranMinLevel, int veteranMinBond, BigDecimal mentorXpBonusMax) {
        public Progression { positive(roleSkillSlots, "roleSkillSlots"); positive(veteranMinLevel, "veteranMinLevel");
            score(veteranMinBond, "veteranMinBond"); fraction(mentorXpBonusMax, "mentorXpBonusMax"); }
    }

    public record CompanionLifecycle(DeathPolicy deathPolicy, Integer maxRevives, FinalState onReviveLimitReached,
                                      InventoryOnFatal inventoryOnFatal, OwnerDeathBehavior ownerDeathBehavior,
                                      Dismiss dismiss, Summon summon, Revival revival) {
        public CompanionLifecycle { Objects.requireNonNull(deathPolicy); Objects.requireNonNull(onReviveLimitReached);
            Objects.requireNonNull(inventoryOnFatal); Objects.requireNonNull(ownerDeathBehavior);
            Objects.requireNonNull(dismiss); Objects.requireNonNull(summon); Objects.requireNonNull(revival);
            if (maxRevives != null) nonnegative(maxRevives, "maxRevives"); }
    }

    public record Dismiss(boolean enabled, boolean allowInCombat, int combatChannelSeconds,
                          int combatCooldownSeconds, boolean requireDismount) {
        public Dismiss { nonnegative(combatChannelSeconds, "combatChannelSeconds"); nonnegative(combatCooldownSeconds, "combatCooldownSeconds"); }
    }

    public record Summon(boolean enabled, int cooldownSeconds, int safeSearchRadiusBlocks, boolean crossWorldTransfer,
                         boolean allowInCombat, int combatExitGraceSeconds) {
        public Summon { nonnegative(cooldownSeconds, "cooldownSeconds"); positive(safeSearchRadiusBlocks, "safeSearchRadiusBlocks");
            nonnegative(combatExitGraceSeconds, "combatExitGraceSeconds"); }
    }

    public record Revival(RevivalTargets allowedTargets, boolean spawnAtStation, BigDecimal stationHealthFraction,
                          BigDecimal instantHealthFraction, BigDecimal stationStaminaFraction,
                          BigDecimal instantStaminaFraction, Recovery instantRecovery, Recovery stationRecovery,
                          int invulnerabilitySeconds, boolean preserveXp, boolean preserveSkills, boolean preserveBond) {
        public Revival { Objects.requireNonNull(allowedTargets); Objects.requireNonNull(instantRecovery); Objects.requireNonNull(stationRecovery);
            fraction(stationHealthFraction, "stationHealthFraction"); positive(stationHealthFraction, "stationHealthFraction");
            fraction(instantHealthFraction, "instantHealthFraction"); positive(instantHealthFraction, "instantHealthFraction");
            fraction(stationStaminaFraction, "stationStaminaFraction"); fraction(instantStaminaFraction, "instantStaminaFraction");
            nonnegative(invulnerabilitySeconds, "invulnerabilitySeconds");
            require(preserveXp && preserveSkills && preserveBond, "Revival must preserve XP, skills and bond"); }
    }

    public record Recovery(BigDecimal temporaryStatMultiplier, int penaltyDurationSeconds, int abilityLockSeconds) {
        public Recovery { fraction(temporaryStatMultiplier, "temporaryStatMultiplier"); positive(temporaryStatMultiplier, "temporaryStatMultiplier");
            nonnegative(penaltyDurationSeconds, "penaltyDurationSeconds"); nonnegative(abilityLockSeconds, "abilityLockSeconds"); }
    }

    public record Genetics(Mutation mutation, WildArcana wildArcana, Registries registries) {
        public Genetics { Objects.requireNonNull(mutation); Objects.requireNonNull(wildArcana); Objects.requireNonNull(registries); }
    }

    public record Mutation(Tiers probabilityByTier, ArcaneFocus arcaneFocus) {
        public Mutation { Objects.requireNonNull(probabilityByTier); Objects.requireNonNull(arcaneFocus); }
    }

    public record Tiers(Chances base, Chances discovery, Chances mastery) {
        public Tiers { Objects.requireNonNull(base); Objects.requireNonNull(discovery); Objects.requireNonNull(mastery); }
    }

    public record Chances(BigDecimal cosmetic, BigDecimal physiological, BigDecimal behavioral, BigDecimal magical) {
        public Chances { fraction(cosmetic, "cosmetic"); fraction(physiological, "physiological"); fraction(behavioral, "behavioral");
            fraction(magical, "magical"); require(cosmetic.add(physiological).add(behavioral).add(magical).compareTo(BigDecimal.ONE) <= 0,
                    "Exclusive mutation probabilities exceed one"); }
    }

    public record ArcaneFocus(boolean enabled, int firstDiscoveryGuaranteeEligibleSuccessfulBirths,
                              boolean guaranteeOncePerPlayerFamily, boolean requiresMastery) {
        public ArcaneFocus { positive(firstDiscoveryGuaranteeEligibleSuccessfulBirths, "firstDiscoveryGuaranteeEligibleSuccessfulBirths");
            require(guaranteeOncePerPlayerFamily && requiresMastery, "Arcane guarantee requires mastery and is once per player/family"); }
    }

    public record WildArcana(BigDecimal ordinaryCandidateRate, BigDecimal affinityCandidateRate, ContentId distributionVersion) {
        public WildArcana { fraction(ordinaryCandidateRate, "ordinaryCandidateRate"); fraction(affinityCandidateRate, "affinityCandidateRate");
            Objects.requireNonNull(distributionVersion); }
    }

    public record RegistryPin(ContentId id, int version, String sha256) {
        public RegistryPin { Objects.requireNonNull(id); positive(version, "registry version");
            new RegistryFingerprint(new RulesetVersion(id, version), sha256); }
        public RegistryFingerprint fingerprint() { return new RegistryFingerprint(new RulesetVersion(id, version), sha256); }
    }

    public record Registries(RegistryPin geneSchema, RegistryPin hybridGraph, RegistryPin expressionRules, RegistryPin morphologySet) {
        public Registries { Objects.requireNonNull(geneSchema); Objects.requireNonNull(hybridGraph);
            Objects.requireNonNull(expressionRules); Objects.requireNonNull(morphologySet); }
    }

    public record Guide(boolean enabled, boolean defaultTutorialOff, boolean skippable, boolean resumable, boolean reactionTimerRequired) {}

    public record Balance(BigDecimal geneticStatMultiplierMin, BigDecimal geneticStatMultiplierMax,
                          int learnedRoleAbilitySlots, int maxInnateActiveSpells) {
        public Balance { positive(geneticStatMultiplierMin, "geneticStatMultiplierMin"); positive(geneticStatMultiplierMax, "geneticStatMultiplierMax");
            require(geneticStatMultiplierMin.compareTo(geneticStatMultiplierMax) <= 0, "Genetic multiplier bounds are reversed");
            positive(learnedRoleAbilitySlots, "learnedRoleAbilitySlots");
            require(maxInnateActiveSpells >= 0 && maxInnateActiveSpells <= 1, "At most one innate active spell is supported"); }
    }

    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
    private static void text(String value, String name) { require(value != null && !value.isBlank(), "Expected nonempty " + name); }
    private static void positive(int value, String name) { require(value > 0, "Expected positive " + name); }
    private static void nonnegative(int value, String name) { require(value >= 0, "Expected nonnegative " + name); }
    private static void score(int value, String name) { require(value >= 0 && value <= 100, "Expected 0..100 " + name); }
    private static void positive(BigDecimal value, String name) { require(value != null && value.signum() > 0, "Expected positive " + name); }
    private static void nonnegative(BigDecimal value, String name) { require(value != null && value.signum() >= 0, "Expected nonnegative " + name); }
    private static void fraction(BigDecimal value, String name) {
        require(value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0, "Expected [0,1] " + name);
    }
}
