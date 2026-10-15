# Genesis — Game Design & Technical Specification

> **A Hytale mod: taming, hybridization, genetics, selective breeding, and creature legacies**  
> **Document version:** 0.5 (consolidated specification + ADR-001)  
> **Date:** October 15, 2026  
> **Status:** Concept / foundation for prototyping and development  
> **Language:** English

---

## Table of Contents

1. [Vision and Principles](#1-vision-and-principles)
2. [Terminology and World Model](#2-terminology-and-world-model)
3. [Core Gameplay Loop](#3-core-gameplay-loop)
4. [Families, Species, and Functional Classes](#4-families-species-and-functional-classes)
5. [Taming: Observation and Trust](#5-taming-observation-and-trust)
6. [Breeding: Enclosures and the Resonance Minigame](#6-breeding-enclosures-and-the-resonance-minigame)
7. [Life Sparks: Selective Breeding Without Armies of Unwanted NPCs](#7-life-sparks-selective-breeding-without-armies-of-unwanted-npcs)
8. [The Genome and Inheritance Rules](#8-the-genome-and-inheritance-rules)
9. [Mutations, Rare Traits, and Weaknesses](#9-mutations-rare-traits-and-weaknesses)
10. [Modular Anatomy and Visual Generation](#10-modular-anatomy-and-visual-generation)
11. [Phenotypes, Attributes, and Balance](#11-phenotypes-attributes-and-balance)
12. [Pedigrees, Inbreeding, and Breeds](#12-pedigrees-inbreeding-and-breeds)
13. [Growth, Training, Commands, and Specializations](#13-growth-training-commands-and-specializations)
14. [Economy, Progression, and Grind Reduction](#14-economy-progression-and-grind-reduction)
15. [Genesis Code and Deterministic Reproduction](#15-genesis-code-and-deterministic-reproduction)
16. [Genesis Chronicles: The History of Discoveries](#16-genesis-chronicles-the-history-of-discoveries)
17. [In-Game Interfaces and UX](#17-in-game-interfaces-and-ux)
18. [Technical Architecture](#18-technical-architecture)
19. [Server Configuration](#19-server-configuration)
20. [Simulations, Testing, and Quality Criteria](#20-simulations-testing-and-quality-criteria)
21. [MVP, Implementation Phases, and Risks](#21-mvp-implementation-phases-and-risks)
22. [Complete Example Gameplay Scenario](#22-complete-example-gameplay-scenario)
23. [Decision Log and Open Questions](#23-decision-log-and-open-questions)
24. [ADR-001: Standalone Architecture and Mod Interoperability](#24-adr-001--standalone-architecture-and-mod-interoperability)

---

## 1. Vision and Principles

**Genesis** is a mod in which players study animal behavior, tame creatures, crossbreed compatible species, discover mutations, establish hereditary lineages, and use the resulting creatures for exploration, combat, travel, and farming.

The focus is not on endlessly farming random pets but on **meaningful decisions in selective breeding**. Crossbreeding should be predictable enough for players to plan outcomes, yet variable enough to make each new combination interesting.

### 1.1. Agreed Design Directions

- **Moderately complex genetics:** diploid loci, hidden alleles, dominance, inheritance, and new mutations; no requirement to simulate chromosomes or biologically accurate meiosis.
- **Modular appearance:** hybrids are assembled from compatible body parts and visual modifiers on a supported skeleton.
- **A fantasy setting:** cross-species hybrids within gameplay-defined families and magical traits are allowed. Biological realism must not come at the expense of engaging gameplay.
- **No single dominant role:** combat companions, mounts, pack animals, resource-producing animals, scouts, support creatures, and collectibles should offer comparable value.
- **Selective breeding without world overcrowding:** offspring initially exist as a “Life Spark”; players decide which creatures to manifest physically.
- **Server-side control:** administrators can configure balance, inbreeding, cooldowns, and minigames.
- **Portable genetic codes:** with the same ruleset, the same Genesis Code reconstructs the same inherited creature configuration.
- **Provenance and history:** breeds, creators, first discoveries, and pedigrees are recorded by the server.
- **Standalone and compatible:** Genesis does not require other mods; it does not take over externally controlled NPCs and offers an optional, versioned extension API (ADR-001, Section 24).

### 1.2. Design Principles

1. **No universally perfect pet:** improving one capability incurs costs, limitations, or reductions elsewhere.
2. **Player knowledge matters, not hidden odds manipulation:** performing well in a minigame increases the chance of a successful breeding attempt but does not alter the inheritance probability of any specific allele.
3. **Every visible change has a cause:** color, silhouette, horns, armor, and glow correspond to genetic traits or cosmetic variants.
4. **Unfavorable outcomes should be explainable:** players can investigate hereditary risks by analyzing genes and pedigrees.
5. **Repetition gets easier:** the first encounter is hands-on and exploratory; mastered actions can be automated.
6. **Genetics and rendering are separate:** limitations in the graphical API must not break the genetics engine or saved data.
7. **The server is authoritative in multiplayer:** clients cannot generate offspring, assign mutations, or register discoveries on their own.

### 1.3. Non-Goals for the First Version

A complete simulation of real-world genetics; arbitrary hybrids between genetic families; on-the-fly generation of new skeletons; unlimited active combat pets; a global cross-server authorship leaderboard; or guaranteed reproduction of legacy models when the required assets are unavailable.

---

## 2. Terminology and World Model

| Term | Meaning |
|---|---|
| **Genetic family (`family`)** | A group of compatible species sharing a common anatomical framework and a permitted set of loci. |
| **Species (`species`)** | An original wild animal, such as a wolf, fox, or horse. |
| **Hybrid archetype (`hybridArchetype`)** | A supported pattern for combining species within a family. This is **not** automatically a new biological species. |
| **Breed (`breed`)** | A stable line established through selective breeding, registered by a player with a set of target traits. |
| **Genome (`genome`)** | A collection of inherited allele pairs. |
| **Locus (`locus`)** | A gene position, such as `FRAME` or `ARCANA`. |
| **Allele (`allele`)** | A gene variant, such as `normal`, `large`, or `shadow`. |
| **Genotype (`genotype`)** | The specific allele pairs carried by an individual. |
| **Phenotype (`phenotype`)** | Calculated expressed traits, attributes, and abilities. |
| **Rig / skeleton** | The bones, animations, attachment slots, and underlying geometry used by a creature. |
| **Life Spark (`spark`)** | A stored genome representing a future creature before physical manifestation. Not an NPC. |
| **Genesis Code** | A portable encoding of the complete genetic representation. |
| **`generationSeed`** | A seed for reproducing a specific breeding calculation with fixed inputs and RNG algorithm. |
| **`creatureId`** | An instance UUID; two animals created from the same code have different IDs. |
| **Discovery** | A record of the first manifestation of a supported archetype or rare form on a server. |

We distinguish four layers:

```text
Genotype → Morphology → Phenotype → Role
(alleles)  (anatomy)    (traits)    (purpose/training)
```

A role is not an inherited allele. Players can change a creature's role within the limits of its physiology.

---

## 3. Core Gameplay Loop

```text
Explore a biome
      ↓
Observe and learn animal behavior
      ↓
Tame animals and acquire breeding stock
      ↓
Breeding enclosure → choose a pair → prepare the environment
      ↓
“Harmony” minigame / automated mode
      ↓
Success → inheritance → new mutation (optional)
      ↓
Life Spark → genetic analysis
      ├─→ store in the Genesis Cradle
      ├─→ disperse and recover some common materials
      └─→ manifest → offspring → maturation
                                 ↓
                    training / role / farming
                                 ↓
                breed registration / new breeding stock
                                 ↺
```

**The central gameplay question:** which genetic outcome is worth keeping for current needs or future selective breeding?

Players should obtain their first useful companion quickly. Multi-generational optimization is an optional long-term objective, not a prerequisite for progressing through ordinary game content.

---

## 4. Families, Species, and Functional Classes

### 4.1. Genetic Families

Family names are part of a **fantasy gameplay taxonomy**, not a strict representation of real-world biological families.

| Family | Starting species | Shared anatomy | Focus |
|---|---|---|---|
| **Fangkin** | Forest wolf, fox, stone jackal | Medium quadrupedal predator | Combat, stealth, tracking |
| **Runners** | Steppe horse, forest deer, sandhorn | Hoofed quadruped | Riding, speed, traversal |
| **Heavyweights** | Moss bison, mountain boar, ash-plated bull | Massive quadruped | Cargo, defense, production |
| **Winged** | Shadow raven, twilight owl, sparkwing | Bird | Scouting, support, materials |

Fantasy species (such as the sparkwing and ash-plated bull) and hybrid names are original Genesis concepts. Their implementation in Hytale must be coordinated with the game's existing content and the mod's asset packs.

### 4.2. Silhouettes and Inheritable Features

- **Wolf:** powerful legs, broad chest, thick neck ruff; strength, keen senses, protective temperament.
- **Fox:** narrow muzzle, large ears, bushy tail; agility, caution, speed.
- **Stone jackal:** bony shoulder protrusions, short coat; resilience, lunging attack.
- **Horse:** long legs, pronounced musculature, mane; saddle compatibility, speed, endurance.
- **Deer:** slender build, branching antlers; maneuverability, jumping ability.
- **Sandhorn:** spiral horns, broad hooves; heat tolerance, energy-efficient running.
- **Moss bison:** massive torso, thick fur, plant-like growths; cargo capacity and wool.
- **Mountain boar:** low, stocky build, tusks; charging attacks, knockback resistance.
- **Ash-plated bull:** thick hide, bony plates; armor and heat tolerance.
- **Raven:** elongated wings; daytime scouting and intelligence.
- **Owl:** broad, silent wings and large eyes; nighttime scouting.
- **Sparkwing:** copper-colored plumage, glowing crest; light- and electricity-related anomalies.

### 4.3. Example Hybrids

| Name | Parents | Appearance | Strength | Trade-off |
|---|---|---|---|---|
| **Wolfox** | Wolf × fox | Wolf-like torso, fox ears and tail | Fast hunter | Less armor |
| **Manestag** | Horse × deer | Horse-like body, branching antlers | Mountain mount | Lower carrying capacity |
| **Ironback** | Bison × ash-plated bull | Heavy body with bony plates | Defense and cargo | Low speed |
| **Moonwing** | Raven × owl | Dark feathers, owl-like head | Night scouting | Fragility, low damage |

A hybrid is not necessarily one fixed model produced by mixing two parents: modules and color depend on the offspring's genotype. The example names designate gameplay archetypes, not separate genetic loci.

### 4.4. Functional Classes

| Class | Primary function | Genetic/anatomical prerequisites |
|---|---|---|
| **Guardian** | Defend its owner and intercept threats | HP, defense, combat-capable limbs |
| **Hunter** | Pursue targets and deal damage | Speed, perception, means of attack |
| **Mount** | Carry the player | Supported rig, saddle, sufficient load capacity |
| **Pack Animal** | Transport items | Cargo-capable rig, endurance, bags |
| **Scout** | Find enemies and points of interest | Perception, mobility |
| **Producer** | Produce wool, feathers, eggs, or special materials | Suitable biological trait |
| **Support** | Provide buffs, light, healing, or warnings | Expressed abilities and suitable behavior |

An animal may perform additional basic functions but has **one primary specialization**. Mount eligibility is determined by anatomy, not merely high strength.

---

## 5. Taming: Observation and Trust

### 5.1. Player Fantasy

Not “feed it three times to get a pet,” but “understand the animal and earn its trust.” The process should become faster once the species has been discovered in the bestiary.

### 5.2. Three Phases

1. **Observation.** Players study behavioral cues, favorite foods, fears, reactions to distance, and interactions with objects.
2. **Contact.** A short behavior-based minigame: choose a sensible action in response to each cue.
3. **Bonding.** Once sufficient trust has been earned, the animal becomes tame, accepts basic commands, and bonds with its owner.

A typical first encounter involves 3–5 decisions. The target duration for a first tame of an animal of familiar difficulty is approximately **3–7 minutes**, including searching and observation (subject to balancing).

### 5.3. State and Actions

The server stores `trust ∈ [0,100]` and `fear ∈ [0,100]`. Behavioral states include *calm, curious, wary, frightened, threatening*.

Example choices: offer suitable food, step back, remain still, let the animal sniff an object, use a toy, or end contact. Their effects depend on `TEMPER`, current state, surroundings, and the chosen action.

| Outcome | Δ trust | Δ fear |
|---|---:|---:|
| Excellent | +25 | −10 |
| Good | +15 | −5 |
| Neutral | +5 | 0 |
| Poor | −5 | +15 |
| Dangerous | −15 | +30 |

Each meter is clamped to `[0,100]`. At `trust = 100`, provided fear is not critical, taming succeeds. At `fear = 100`, the animal flees; an aggressive animal may defend itself. Failure **does not erase all progress**: some trust remains (a suggested value is 60%).

### 5.4. Temperaments

| Temperament | Behavior |
|---|---|
| Curious | Responds well to unfamiliar objects |
| Cautious | Needs space and slow actions |
| Aggressive | Reacts more strongly to intrusions |
| Friendly | Builds trust faster |
| Skittish | Higher penalty for mistakes |
| Independent | Harder to bond with; useful for autonomous tasks |

Cues must provide fair information to the player. Unexplained traps—where the same apparent situation produces opposing outcomes without any visual indication—are unacceptable.

### 5.5. Family-Specific Differences

- **Fangkin:** distance, reactions to contact and food.
- **Runners:** calm approaches and avoiding sudden movement.
- **Heavyweights:** safe territory, food, and stress reduction.
- **Winged:** observation, lures, object placement, and attracting animals from a distance.

The first encounter involves the full minigame. Faster taming becomes available after researching a species in the bestiary; servers may also disable minigames entirely.

### 5.6. Accessibility

The minigame should not require fast button presses or pixel-perfect cursor positioning. It needs an untimed, turn-based option, readable cues, adjustable animations, and the ability to retry an interaction without excessive punishment.

---

## 6. Breeding: Enclosures and the Resonance Minigame

### 6.1. Items and Structures

- **Breeding Enclosure:** houses a compatible pair, lets players configure the environment, and initiates breeding attempts.
- **Genesis Cradle:** stores Life Sparks and manifests selected genomes.
- **Breeder's Laboratory:** analyzes hidden genes and inheritance probabilities.
- **Genesis Journal:** tracks breeding goals, pedigrees, discoveries, and pets.

Each family can have enclosure upgrades: a woodland corner, an open exercise area, a sheltered space, or a nesting site. These are gameplay conditions, not modifications to DNA inheritance rules.

### 6.2. Preconditions

1. Both parents are adults, available, and belong to supported genetic families.
2. Cross-family breeding is prohibited in the standard mode.
3. Both parents are ready, neither is on cooldown, and neither is involved in another attempt.
4. Food, environmental, and other common resource requirements are satisfied.
5. The enclosure/Cradle has room for the result.
6. The server allows breeding, and the pair is not blocked by its relatedness policy.

The baseline compatibility is **0.90** for the same species or the same registered stable breed; **0.70** for different species within one family; and **0** for different families. The default for hybrids within one family is 0.70 unless specific rules say otherwise.

### 6.3. The “Harmony” Minigame

Three short rounds (targeting a total of 30–60 seconds for first-time players):

1. **Environment:** choose suitable food, shelter, or conditions from available objects.
2. **Behavior:** identify the right time to bring the parents together based on both animals' current states.
3. **Resonance:** synchronize two short visual rhythms or solve an equivalent turn-based puzzle.

The first two rounds reward animal knowledge; the third rewards familiarity with the shared mechanic. Resonance does not edit alleles or guarantee rare mutations.

**Automation:** once players have mastered a family, automated breeding becomes available, with a slightly smaller bonus to the success probability. Fully automated breeding must not outperform a correctly completed manual minigame.

### 6.4. Breeding Success Formula

\[
P_{success} = \operatorname{clamp}(C \cdot R_A \cdot R_B \cdot E \cdot Q,\ 0,\ 0.95)
\]

- `C`: 0.90 / 0.70 / 0, depending on compatibility.
- `R_A`, `R_B`: parental readiness, each in `[0,1]`.
- `E`: enclosure quality, initially in `[0.8, 1.1]`.
- `Q`: minigame outcome, initially in `[0.9, 1.15]`.

Example: `0.70 × 1 × 1 × 1.1 × 1.15 = 0.8855` → **88.55%**. Incompatible pairs cannot initiate breeding; the system should not simply proceed with a zero-percent success chance.

**Failed attempt:** creates no Spark, consumes one attempt and some materials, and puts the pair into recovery. **Successful attempt:** creates one Spark genome. Genetic randomness is calculated **only after success has been confirmed**.

### 6.5. Why the Minigame Does Not Edit Genes

If both parents are `S/s`, the chance of an `s/s` offspring is always 25% following a successful birth (in the absence of a new mutation), regardless of player skill. Minigame performance increases the number of successful attempts for a given resource budget; it does not secretly improve the odds of inheriting a desired allele.

---

## 7. Life Sparks: Selective Breeding Without Armies of Unwanted NPCs

### 7.1. The Problem

At a 3.125% chance of the desired combination, the expected number of **successful births is 32**. If every one of them creates a living pet, the farm quickly turns into a storage facility for unwanted NPCs. Memory usage and enclosure requirements rise, and players face the emotionally unpleasant task of disposing of animals.

### 7.2. Solution: Life Sparks

Successful breeding creates a **Life Spark**—a record containing an already determined genome, potential appearance, and provenance metadata. It is **not yet a physical animal**.

Players can:

- **Store** a Spark in the Genesis Cradle.
- **Analyze** the traits they are currently able to inspect.
- **Manifest** the Spark as offspring with a fixed genome.
- **Disperse** the Spark to free storage space.

Dispersal does not count as the death of a pet: no living creature has yet come into existence.

### 7.3. Spark Economy

- One successful breeding attempt creates **one** Spark.
- The default capacity is **8** for a basic Cradle and **32** for an upgraded Cradle.
- Dispersal **does not** restore parental readiness or allow players to reroll the same result.
- Refunds are limited to **25% of common consumable materials**, excluding rare catalysts and recovery time.
- The game must prevent resource-generating loops involving dispersal and repeated breeding.
- Sparks can be stored as data records, without entities, AI ticks, food consumption, or animations.

### 7.4. Levels of Spark Genetic Analysis

| Player level | Available information |
|---|---|
| Beginner | Family, approximate silhouette, visible expressed traits |
| Breeder | Known genetic markers, attribute ranges, obvious weaknesses |
| Geneticist | Full genome, recessive alleles, exact genetic potential |

Analysis **never changes** the Spark. When information is incomplete, the game should display “unknown” or a range rather than inventing precision.

### 7.5. Automatic Filters

Later in progression, players can keep Sparks with selected traits and automatically disperse the rest. Filters may use **only information available at the player's current analysis level**; otherwise they would act as a free hidden-gene sequencer.

### 7.6. Manifestation and the Fate of Living Animals

Manifested animals go through growth stages. They can be kept in a collection, trained, used on a farm, transferred to another player, retained as breeding stock, or released into the wild. Large-scale selection should not require manifesting every undesirable genotype.

---
## 8. The Genome and Inheritance Rules

### 8.1. Formal Model

The MVP uses **12 independent diploid loci**. Each locus stores a pair of alleles in a fixed serialization order, conceptually inherited one from each parent. Pair order does not matter for simple expression calculations; parental origin can be stored separately when needed.

\[
G = \{(a_1,b_1), (a_2,b_2), \ldots, (a_{12},b_{12})\}
\]

| Locus | Category | Main purpose |
|---|---|---|
| `FRAME` | Morphology | Size, mass, torso |
| `HEAD` | Morphology | Muzzle, head, ears |
| `LIMBS` | Morphology | Limbs, gait, jumping |
| `APPENDAGE` | Morphology | Tail, horns, growths |
| `PIGMENT` | Appearance | Color, patterns |
| `COVER` | Appearance | Fur, feathers, hide |
| `MUSCLE` | Physiology | Damage, cargo, resource consumption |
| `STAMINA` | Physiology | Endurance, recovery |
| `METABOLISM` | Physiology | Food, growth, regenerative costs |
| `SENSE` | Perception | Sight, hearing, smell |
| `TEMPER` | Behavior | Trust, reactions, trainability |
| `ARCANA` | Magic | One expressed active inherited ability |

A locus may have more than two **possible alleles across the population**, but each animal carries exactly two per locus. The number of loci may expand in future schema versions.

### 8.2. Inheritance

At an `A/a` locus, a parent passes on `A` or `a` with a 50% probability each. At an `A/A` locus, it passes on `A` with a 100% probability. MVP loci are independent; chromosomal linkage and crossing over are not modeled.

Example: `S/s × S/s`:

|  | S | s |
|---|---|---|
| **S** | `S/S` — 25% | `S/s` — 25% |
| **s** | `s/S` — 25% | `s/s` — 25% |

If `S` is dominant, `s/s` is expressed in 25% of births, while `S/s` carriers account for 50%.

### 8.3. Allele Expression

Each locus supports:

1. **Complete dominance:** one copy masks a recessive trait.
2. **Incomplete dominance:** a heterozygote expresses an intermediate phenotype.
3. **Additive inheritance:** alleles contribute modest effects that add together.

For multi-allelic loci, pairs are resolved through `expressions` lookup tables rather than one global “dominance level.” Every allowed combination must have a defined outcome.

Example: `FRAME`:

| Pair | Expression |
|---|---|
| `normal/normal` | Normal body |
| `normal/large` | Large body |
| `large/large` | Very large body |
| `small/large` | Medium-sized body |
| `small/small` | Small body |

### 8.4. Combining Probabilities

For independent target traits:

\[
P(\text{all traits}) = \prod_i P_i
\]

If three traits have probabilities `0.50`, `0.25`, and `0.25`:

\[
P = 0.50 \cdot 0.25 \cdot 0.25 = 0.03125 = 3.125\%
\]

The probability of obtaining at least one desired genotype in `n` **successful** breeding events is:

\[
P_{\ge1} = 1-(1-P)^n
\]

With `P = 0.03125` and `n = 32`, the probability is approximately **63.8%**, not 100%. The expected number of successful births before the first success is `1/P = 32`. Failed breeding attempts are counted separately.

### 8.5. Compatibility

| Pairing | Tier | Base success chance |
|---|---|---:|
| Same original species | A | 90% |
| Different species, same family | B | 70% |
| Compatible hybrids, same family | B (default) | 70% |
| Same registered stable breed | A (optional rule) | 90% |
| Different families | X | Not allowed |

Membership in the same breed only increases the probability of a **successful breeding attempt**; it **does not change allele inheritance probabilities**. The bonus can also be disabled in configuration.

A separate `rigCompatibility` validation is required: each genetic family must have supported anatomical outcomes. Magical cross-family hybrids are a future feature based on manually authored archetypes—not a universal combinatorial system.

### 8.6. Calculation Sequence

```text
validateParents()
validateFamilyAndRig()
validateBreedingRules()
rollBreedingSuccess()

for each locus:
    alleleA = drawOne(parentA[locus])
    alleleB = drawOne(parentB[locus])
    child[locus] = canonicalPair(alleleA, alleleB)

rollDeNovoMutation()
if mutation:
    selectCompatibleCategoryAndLocus()
    changeOneAllele()

validateGenomeAgainstRuleset()
resolveMorphology()
resolvePhenotype()
calculateAncestryAndKinship()
createSparkRecord()
persistAtomically()
```

**Safety rule:** do not reroll inherited alleles merely because the resulting appearance is inconvenient. Variant compatibility is established **before breeding begins**, and the phenotype must have a deterministic visual mapping or a predefined valid fallback that does not change the genotype.

---

## 9. Mutations, Rare Traits, and Weaknesses

### 9.1. New (De Novo) Mutations

Initial distribution **per successfully generated offspring**:

| Outcome | Probability |
|---|---:|
| No new mutation | **92.0%** |
| Cosmetic mutation | **4.0%** |
| Physiological mutation | **2.5%** |
| Behavioral/sensory mutation | **1.0%** |
| Anomalous (magical) mutation | **0.5%** |

The total chance of a new mutation is **8%**. The MVP allows no more than **one new mutation per birth**. Existing mutations are not subject to this limit; they are inherited like ordinary alleles.

The algorithm selects a category, an eligible locus, and a new allele, then replaces one inherited copy. If a category/allele is unavailable for the combination, an explicitly defined skip policy applies; the entire genome must not be silently replaced.

### 9.2. Benefits and Costs

A suggested distribution for new non-cosmetic mutations is 30% predominantly beneficial, 40% beneficial with a significant cost, and 30% predominantly harmful. These are **generator weights**, not a universal assessment of usefulness.

| Mutation | Benefit | Cost / downside |
|---|---|---|
| Hypertrophy | +15–20% physical damage | Lower speed, increased appetite |
| Protective plates | +18–25% defense | Additional mass, lower mobility |
| Lightweight skeleton | Speed and jumping | Lower HP, fragility |
| Enhanced lungs | Increased endurance | Limits on peak strength output |
| Regeneration | Recovers HP outside combat | Significant food/reserve consumption |
| Venom glands | Damage over time | Weaker direct attacks |
| Thermal vision | Detects concealed targets | Limitations in ordinary perception |
| Glowing mane | Illumination | Cannot remain fully concealed |
| Dense undercoat | Cold protection | Overheating in hot environments |
| Faster fur growth | Increased resource production | Higher food consumption |
| Reinforced tendons | Higher jumps | Drawbacks under heavy loads |

Some alleles are simply unfavorable:

| Allele | Locus | Possible expression |
|---|---|---|
| `fragile_bones` | `LIMBS` | Reduced HP |
| `weak_joints` | `LIMBS` | Reduced carrying capacity |
| `unstable_metabolism` | `METABOLISM` | Greatly increased food consumption |
| `poor_vision` | `SENSE` | Weaker detection |
| `nervous` | `TEMPER` | May interrupt commands under stress |
| `weak_wings` | `LIMBS` (avian set) | Reduced flight duration |

An effect may be recessive, dominant, or intermediate; this is determined by the rules for the relevant allele.

### 9.3. Magical Traits

The `ARCANA` locus supports `dormant`, `shadow_affinity`, `regeneration`, `spark_affinity`, `toxin_affinity`, and future variants.

**At most one expressed, active, inherited magical ability** is allowed under the initial rules. When two different ability alleles are present, the expression table defines a single outcome and any possible hidden carrier states. Passive effects from other loci are allowed but constrained by balance.

Examples of active abilities include a shadow dash, shield, electric discharge, healing, and echolocation. Their parameters come from separate ability definition files; the gene unlocks access, but not unlimited free power.

### 9.4. Gene Interactions

| Combination | Result |
|---|---|
| Large body + developed muscles | Powerful charge at a high stamina cost |
| Large body + weak joints | Stronger cargo restrictions |
| Dense undercoat + cold resistance | Excellent performance in freezing conditions |
| Dense undercoat + heat | Risk of overheating |
| Night vision + quiet movement | Effective night scout |
| Regeneration + fast metabolism | Fast healing, expensive upkeep |

Interactions are defined **explicitly**. The system should not multiply an unlimited number of positive bonuses together.

### 9.5. Sources of Rare Genes

Two routes: random new mutations and **wild carrier lineages** found in rare biomes. Discovering a new population should be a viable alternative to breeding enormous numbers of animals in one location.

---

## 10. Modular Anatomy and Visual Generation

### 10.1. Goal

Different genotypes should produce visually distinguishable animals through torso shape, muzzle, ears, tail, horns, fur, feathers, plates, size, and color. At the same time, every variant must work correctly with animation and collision systems.

### 10.2. Appearance Layers

```text
rig (quadrupedal predator / hoofed / heavy / bird)
  ├── body module
  ├── head module
  ├── limb module set
  ├── appendage module(s): tail / horns / crest
  ├── cover layer: fur / feathers / hide
  ├── pigment + patterns
  ├── cosmetic / magical overlays
  └── equipment sockets: saddle / bags / harness
```

For the MVP, it is better to author compatible variants for a given rig in advance than to attempt arbitrary runtime geometry morphing or the generation of new bones.

### 10.3. Mapping Genes to Visual Modules

| Locus | Allele | Visual expression |
|---|---|---|
| `FRAME` | `large` | Large torso |
| `HEAD` | `fox` | Narrow muzzle and large ears |
| `LIMBS` | `runner` | Long, slender limbs |
| `APPENDAGE` | `bushy_tail` | Full, bushy tail |
| `PIGMENT` | `shadow` | Dark markings |
| `COVER` | `dense_fur` | Long fur |
| `ARCANA` | `spark_affinity` | Glowing effects when the ability is expressed |

A gene determines a **semantic trait**. A separate Morphology Resolver selects a permitted model, texture, or attachment from a registry. An unused or hidden allele does not necessarily change the appearance.

### 10.4. Module Contract

```json
{
  "moduleId": "fox_head_02",
  "slot": "head_socket",
  "allowedRigs": ["quadruped_medium"],
  "modelAsset": "genesis/heads/fox_02",
  "textureVariants": ["normal", "shadow", "albino"],
  "requiredTags": ["canid"],
  "providedTags": ["long_ears"],
  "animationProfile": "quadruped_medium_v1",
  "hitboxProfile": "head_medium"
}
```

Slot and asset names are part of the proposed Genesis API; they are **not claims about existing Hytale identifiers**.

### 10.5. Appearance Invariants

- Only use modules permitted for a given slot and rig.
- Attachment points and animations must be compatible.
- Collision geometry and mount seating must match creature size.
- Visible wings alone do not automatically grant flight.
- Every valid genotype must have a stable visual representation.
- The same code + the same assets + the same ruleset must produce the same appearance.
- Physical and cosmetic variations must not modify server-side genetics.

### 10.6. Technical Risk

**Critical project risk:** it is not yet established to what extent a particular Hytale version permits dynamic assembly of skeletal model parts for an individual NPC without animation and synchronization limitations. The first technical prototype must verify swapping a head, tail, texture, and scale on one NPC, including network visibility, persistence, and collision.

**Fallback:** prebuilt complete body variants combined with dynamic attachments and textures, without changing the underlying genome model.

---

## 11. Phenotypes, Attributes, and Balance

### 11.1. Genetic Potential

For attribute `k`:

\[
S_k = \operatorname{clamp}\left(B_k \cdot \left(1+\sum_i g_{i,k}+\sum_j I_{j,k}\right), L_k, U_k\right)
\]

- `B_k` is the baseline profile of the anatomical archetype.
- `gᵢ,ₖ` is the contribution of **expressed** genes.
- `Iⱼ,ₖ` represents predefined interactions.
- `L_k`, `U_k` are the limits for that archetype/role.

Current in-game effectiveness:

\[
S_k^{actual} = S_k \cdot T_k \cdot H_k \cdot E_k
\]

`T_k` is training; `H_k` is health, fatigue, and satiety; `E_k` is the environment. These factors are **not inherited**. The same mutation must not be counted twice through both genetics and metabolism.

### 11.2. Normalized Attributes

Health, strength, defense, speed, stamina, agility, carrying capacity, perception, trainability, and metabolism. A 0–100 normalized scale is used in the UI and simulator; actual HP, damage, and speed units must be calibrated for Hytale.

Initial **relative** modifier ranges compared with the base archetype:

| Attribute | Lower limit | Upper limit |
|---|---:|---:|
| HP | 70% | 145% |
| Strength | 70% | 135% |
| Defense | 70% | 140% |
| Speed | 75% | 130% |
| Stamina | 70% | 150% |
| Cargo capacity | 60% | 155% |
| Perception | 70% | 145% |

Reaching all maxima simultaneously must be impossible because of genetic trade-offs, incompatibilities, and limitations on arcane abilities.

### 11.3. Proposed Starting Profiles (Illustrative Scale)

| Species | HP | Strength | Speed | Cargo | Perception |
|---|---:|---:|---:|---:|---:|
| Wolf | 65 | 70 | 75 | 15 | 70 |
| Fox | 35 | 40 | 90 | 5 | 95 |
| Horse | 70 | 35 | 80 | 70 | 50 |
| Deer | 55 | 30 | 90 | 35 | 75 |
| Bison | 95 | 55 | 40 | 100 | 40 |
| Boar | 85 | 75 | 55 | 65 | 35 |
| Raven | 30 | 20 | 75 | 0 | 90 |
| Owl | 35 | 35 | 65 | 0 | 100 |

Flight and ground movement use different actual units and rules; the values above are for design comparisons only.

### 11.4. Energy Costs

Three derived resources: **stamina** (physical work), **metabolic reserve** (nutrition/recovery), and **arcane reserve** (magical abilities). They are not additional mandatory loci.

Greater mass, armor, musculature, regeneration, and glowing traits increase costs or reduce other capabilities. Understandable trade-offs are preferable to arbitrary penalties.

Example baseline food-consumption formula:

\[
F = F_0(1+0.30\max(0,M-1)+0.20\max(0,P-1)+0.25A)
\]

`M` is relative mass, `P` is relative muscle development, and `A` is the load imposed by active arcane processes, from 0 to 1. Additional modifiers from the specific genome apply **without counting the same effect twice**.

### 11.5. Ecology and Environment

| Biome | Advantages | Costs |
|---|---|---|
| Snow | Dense fur, cold resistance | Heat-loving animals perform worse |
| Desert | Light build, efficient metabolism | Thick-coated animals overheat |
| Forest/caves | Agility, night vision | Very large mounts face restrictions |
| Swamp | Moisture resistance, low mass | Heavy animals struggle to move |
| Mountains | Jumping, sturdy joints | Large loads hinder movement |

### 11.6. Gameplay Roles and Balance Targets

- **Combat:** one attacking companion should contribute approximately 20–40% of the sustained PvE DPS of a comparably progressed player. Powerful moves have cooldowns and costs; PvP is balanced separately.
- **Mounts:** speed, jumping, cargo, traversability, and defense compete with one another. Mounting requires an appropriate rig and saddle attachment point.
- **Pack animals:** small animals have 6–10 inventory slots; medium animals, 10–16; heavy animals, 16–24 (initial proposal; later replace with a weight-based system).
- **Scouting:** detecting enemies, tracks, and points of interest; weak direct damage.
- **Production:** wool, feather, egg, and fantasy-material production cycles consume food and time; genetic productivity gains should initially be capped at roughly 30–40%.
- **Support:** temporary buffs, warnings, illumination, and limited healing.

Loaded pack-animal speed:

\[
V_{loaded}=V_0 \cdot \left(1-0.35\max\left(0,\frac{q-0.5}{0.5}\right)\right),\quad q\in[0,1]
\]

There is no speed penalty up to half capacity; at full capacity, speed is approximately 65% of normal. Eventually `q` should reflect carried weight, not just occupied slots.

### 11.7. Limits on Companion Armies

Initial server rule: **two active companions** and **one currently used mount**; the rest remain in the player's animal housing. Players should not be able to clear all content using a passive army of dozens of combat NPCs.

---
## 12. Pedigrees, Inbreeding, and Breeds

### 12.1. Pedigrees

Every manifested individual stores its own UUID, the UUIDs of its parents, its generation, the original proportions of its ancestry, and pedigree references. For hybrids, ancestry proportions are calculated as averages:

\[
A_{child}(s) = \frac{A_{parent1}(s)+A_{parent2}(s)}{2}
\]

Wolf × fox → 50/50; that hybrid × wolf → 75/25. **Ancestry percentages do not grant additional attributes beyond those produced by the actual alleles.**

### 12.2. Baseline Expression of Recessive Genes

Crossing two carriers of a harmful `a` allele produces `a/a` with a 25% probability, regardless of whether the parents are related. Relatives are more likely to share inherited alleles, making pedigrees useful, but ordinary inheritance should not be replaced with an arbitrary penalty.

### 12.3. Inbreeding Coefficient

\[
F_{child} = \phi(parentA,parentB)
\]

where `φ` is the kinship coefficient calculated from the pedigree.

| Parental relationship (assuming unrelated founders) | Offspring F |
|---|---:|
| Unrelated | 0% |
| First cousins | 6.25% |
| Half-siblings | 12.5% |
| Parent × offspring | 25% |
| Full siblings | 25% |

For the MVP, pedigree calculations may be limited to four known generations, displaying a “partial pedigree” indication. A missing record of a shared ancestor does not prove that the animals are actually unrelated.

### 12.4. Configurable Gameplay Frailty

In addition to ordinary inheritance, Genesis may apply an **optional gameplay effect** called `developmentalFrailty`, which is not itself inherited as a new gene. If enabled on a server, it increases risk for very closely related lineages.

\[
P_{frailty}=\min(P_{max},K\cdot\max(0,F-F_0))
\]

The default soft-mode values are `F₀ = 0.0625`, `K = 0.2`, and `Pmax = 0.15`. For `F = 0.25`, this gives an additional risk of **3.75%**. If the effect triggers, it might, for example, reduce HP by 8% for **that individual only**, without introducing a separate heritable allele.

**Crucial distinction:** the inbreeding setting enables or disables this additional gameplay penalty but does not change standard Mendelian inheritance probabilities.

### 12.5. Server Modes

| Mode | Rules |
|---|---|
| `off` | No additional penalties or restrictions; pedigrees may still be recorded |
| `soft` (default) | Small, configurable additional risk |
| `strict` | Increased risk and optional prohibition of pairings above a chosen `F` threshold |

Administrators can configure the parameters independently. Changing these settings **must not rewrite** existing genomes or already recorded congenital conditions.

### 12.6. Breed Registration

A breed is a named selective-breeding lineage, not a new set of genetic rules. Proposed requirements:

- at least **3 generations**;
- at least **8 evaluated offspring**;
- target traits expressed in at least **75%** of the evaluated individuals;
- no critical hereditary defects in the registered breeding stock.

Registration does not make genes dominant or increase their inheritance probability. It provides a name, a journal entry, a history, and collectible value. Administrators can adjust the criteria.

---

## 13. Growth, Training, Commands, and Specializations

### 13.1. Life Stages

| Stage | Capabilities |
|---|---|
| Offspring | Care, bonding, early phenotype expression |
| Juvenile | Basic training, limited commands |
| Adult | Full role, breeding, equipment |
| Elder (optional) | Activity limits, veteran status |

**Death from old age should not be mandatory by default.** A realism-focused mode can be offered as a server option.

### 13.2. Training

Training improves obedience and effectiveness within genetic potential, but **does not alter the genome** and is not inherited. Trust affects how reliably commands are followed, not damage directly.

### 13.3. Basic Commands

- `Follow` — follow the player.
- `Guard` — guard an area.
- `Defend` — defend the owner.
- `Attack` — attack the selected target.
- `Search` — search for tracks or resources.
- `ReturnHome` — return to assigned animal housing.
- Later: `Fetch`, `Distract`, `Carry`, routes, and autonomous tasks.

### 13.4. Combat Roles

Guardian/tank, hunter/assassin, controller, and support. AI must account for distance, condition, assigned behavior mode, threats, and available paths. Advanced tactical autonomy is not an MVP requirement.

---

## 14. Economy, Progression, and Grind Reduction

### 14.1. Four Progression Tiers

1. **Beginner:** taming, simple Sparks, approximate trait information.
2. **Breeder:** compatibility information, the journal, Cradle upgrades.
3. **Selective Breeder:** pedigrees, pairing recommendations, automation of familiar minigames.
4. **Geneticist:** full genome analysis, advanced filters, parallel enclosures, rare lineages.

Experience unlocks **tools**, not a global bonus to the chances of obtaining desired alleles. Players can seek rare genes in the world instead of relying solely on random breeding attempts.

### 14.2. Reducing Repetitive Work

- Repeatedly taming an already studied species becomes easier.
- Breeding within a mastered family can be automated.
- Undesirable genomes can be dispersed rather than manifested.
- The Breeding Journal shows objectives and suitable pairings.
- Spark filtering avoids inspecting every Spark manually but does not reveal unknown genes for free.
- Multiple breeding enclosures can operate in parallel; **an animal may participate in only one attempt at a time**.
- Rare traits occur in wild populations, encouraging biome exploration.

### 14.3. Progression Pace Targets (Playtesting Only)

| Event | Target pace |
|---|---|
| First tame | ~3–7 minutes |
| First hybrid offspring after obtaining a pair | ~10–20 minutes |
| First useful rare trait | ~30–60 minutes of active play |
| Complex multi-trait combination | Several play sessions; not mandatory for progression |

Both median and high-percentile waiting times matter: unlucky players must not be trapped in an apparently endless sequence of hundreds of required attempts. For mandatory story objectives, a guaranteed path to a **functionally adequate** pet is preferable to guaranteeing a specific rare genotype.

### 14.4. Farm Economy

Resource production requires time, food, and space; special breeding lines improve efficiency within limits. Basic production formula:

\[
Y = Y_0 \cdot G \cdot N \cdot E
\]

`Y₀` is baseline output per cycle; `G` is the genetic modifier; `N` reflects nutrition and health; `E` represents environmental conditions. Initial genetics-based productivity gains should be limited to roughly 30–40% above a normal lineage, pending balance tests.

### 14.5. Multiplayer

Purchasing, trading, animal boarding facilities, and breed markets are optional later features. Trading must not transfer first-discoverer status, falsify provenance, or disclose a hidden genome without the owner's consent if the server enables genetic privacy.

---

## 15. Genesis Code and Deterministic Reproduction

### 15.1. Three Distinct Identifiers

| Identifier | Purpose |
|---|---|
| **`genesisCode`** | Reconstructs inherited content and deterministic cosmetic variants. Identical genotypes may share the same code. |
| **`generationSeed`** | Reproduces a particular breeding process only when the inputs, parents, and RNG algorithm version are known. |
| **`creatureId`** | The unique UUID of each manifested creature instance. |

A separate `breedingEventId` / `sparkId` establishes unique provenance. **Do not insert a random `birthNonce` into the genetic code itself** if the objective is to produce identical codes for genetically and cosmetically identical animals. Instance-specific passports should use event metadata instead.

### 15.2. What the Code Reproduces

- genome schema version;
- genetic ruleset ID and compatible asset set;
- genetic family and permitted archetype;
- every allele pair, including recessive alleles;
- a fixed cosmetic variant seed or variant IDs;
- every inherited source parameter relevant to morphology.

The code **does not contain** the owner, instance UUID, current HP, food, inventory, learned skills, discovery status, or full pedigree history.

### 15.3. Format

```text
GEN1-<Base32(canonical_binary_payload)>-<checksum>
```

An **illustrative, nonfunctional shortened example**, for documentation only:

```text
GEN1-7KD4-V9MP-3TR6-...-CHK
```

A complete self-contained code may be tens or hundreds of characters long, depending on the eventual schema. A checksum detects copying errors but **does not authenticate provenance** and is not a digital signature.

### 15.4. Determinism

\[
Phenotype = Resolve(Genome, RulesetVersion)
\]

The same genome, ruleset, and compatible model assets must produce the same result. All additional random cosmetic decisions must either be included in the code or be derived deterministically from an included seed using a fixed algorithm.

Two modes are possible across updates:

1. **Genetic reproduction:** the genome remains unchanged; its phenotype is recalculated using the current ruleset.
2. **Exact reproduction:** the specified ruleset and assets must be supported; otherwise, the system reports an explicit error/warning instead of silently substituting a different creature.

Schema migrations, rules for removing or renaming alleles, and a version-compatibility matrix are required.

### 15.5. Import and Security

- Players can export their own genomes when the setting is enabled.
- Spawning from a code is **administrator-only by default**; it does not automatically grant achievements or first-discovery records.
- Ordinary imports are disabled on public survival servers by default.
- Code validation includes length, version, checksum, unknown alleles, incompatible rigs, attribute limits, and asset availability.
- If verifiable cross-server pedigrees are needed later, they will require **a separate cryptographic server signature**, not a checksum.

Proposed commands (not existing Hytale commands):

```text
/genesis spawn <genesisCode>
/genesis inspect <creatureId>
/genesis export <creatureId>
/genesis breed --parentA <id> --parentB <id>
/genesis simulate --parentA <id> --parentB <id> --count 10000
```

`simulate` does not create entities or consume in-game breeding attempts. For 12 independent loci, analytical calculations are preferable for exact probabilities; simulations are useful for verification and complex interactions.

**Interoperability:** if external genes or anatomy modules are registered, Genesis Code stores namespaced IDs, required extension versions, and canonically serialized payloads. If an extension is missing, genetic data is preserved, but import/spawn operations requiring unknown rules fail without creating a partial entity. See ADR-001, Section 24.7.

### 15.6. External Calculator

A future website could decode Genesis Codes, display creature models and attributes, compare parents, calculate inheritance probabilities, choose optimal pairs, and share genetic passports.

The genetics core must provide **a documented format and test vectors** so Java and TypeScript/WASM implementations produce identical results.

---

## 16. Genesis Chronicles: The History of Discoveries

### 16.1. Not Every Genome Is a New Species

Unique allele combinations occur frequently. Therefore, discoveries are recorded at three levels:

| Record type | Trigger |
|---|---|
| **Hybrid archetype** | First manifestation of a supported cross-species hybrid type |
| **Rare hereditary form** | First manifestation of a predefined rare combination |
| **Registered breed** | Stability criteria are met and the breed is registered |

### 16.2. Attribution

- **First Discoverer:** the first player to manifest a new form/archetype on the server, according to the world's rules.
- **Breeder:** the player who created a particular Spark.
- **Breed Founder:** the player who registered a stable lineage.
- **Owner:** the current owner of a specific animal.

Historical records and first-discovery credits are tied to stable player UUIDs, not display names.

### 16.3. When a Discovery Is Recorded

A form is recorded at its **first physical manifestation**, not when the Spark is created. The Spark's creator remains recorded in its provenance. If another player manifests it, the rules distinguish between the breeder and the first discoverer.

Technically, registration must use an atomic unique record keyed by `(worldId, discoveryKey)`; if two events race, only one manifestation receives the first-discovery credit.

### 16.4. Abuse Prevention

Admin-spawned animals and genomes imported into the server **do not count toward first discoveries by default**. Transferring a pet does not alter original credits. Part of a rare animal's genome may remain private.

**External provenance:** admin spawning and imported extension genomes do not qualify for first discoveries by default. Authorship and authority over a particular NPC are separate concepts; integrations may not rewrite the provenance of another breeding line. See ADR-001.

### 16.5. Lineage History

The server maintains a graph of ancestors, descendants, generations, genetic changes, acquired traits, authors of important events, and dates when breeds were discovered. Later features may include public collection exhibitions and a shared web catalog with an explicit cross-server trust policy.

---

## 17. In-Game Interfaces and UX

### 17.1. Creature Profile

Displays name, hybrid archetype, breed, age, owner, primary role, normalized attributes, known strengths and weaknesses, commands, and the Genesis Code when the player has permission.

### 17.2. Bestiary

Lists researched species, foods, habits, environments, likelihood of finding rare wild lineages, and the history of first encounters. Some information remains hidden until discovered.

### 17.3. Breeding Enclosure

Allows selection of two parents and displays compatibility, reasons a pairing is disallowed, readiness, environmental conditions, kinship risk, and a “Start Harmony” action. Unknown genes must yield a forecast **based on available information**, not false precision.

### 17.4. Genesis Cradle

Displays stored Sparks, filters, genetic-profile comparisons, detailed analysis, and the actions “Manifest” and “Disperse.” Irreversible dispersal of unique or favorited Sparks must require confirmation.

### 17.5. Genetics Laboratory

Displays the genome across 12 loci, known and hidden alleles, phenotype forecasts, parental combinations, and probabilities for specific breeding goals.

### 17.6. Breeding Journal

Allows players to define a “desired pet” through traits, role, minimum attributes, and unacceptable weaknesses. The system recommends suitable pairings and explains the results.

### 17.7. Chronicles

Provides server discoveries, attribution, a breed map, pedigree graphs, rare-form exhibits, and histories of lineage changes.

### 17.8. UX Rules

- Distinguish the **probability of a successful breeding attempt** from the **probability of a desired genotype given successful breeding**.
- Do not reveal hidden alleles before the corresponding research.
- Always explain incompatibility and why mounting or equipping is impossible.
- Provide cancellation/confirmation for irreversible actions, especially dispersing valuable Sparks.
- Make minigames accessible without timers and with adjustable effects.
- Do not force players to switch constantly among dozens of animals to perform one operation.

---
## 18. Technical Architecture

### 18.1. Separation of Responsibilities

```text
Genesis Core (Java, independent of Hytale)
 ├── Family / Species / Allele Registries
 ├── Compatibility Engine
 ├── Inheritance Engine
 ├── Mutation Engine
 ├── Expression / Phenotype Resolver
 ├── Morphology Planning / Validation
 ├── Kinship Engine
 ├── Genome Codec + Version Migration
 ├── Probability Calculator
 └── Deterministic Test Suite

Genesis Hytale Adapter
 ├── NPC lifecycle / AI integration
 ├── Taming interaction
 ├── Breeding structures and events
 ├── Entity components / persistence
 ├── Models / attachments / rendering
 ├── UI and input
 ├── Permissions / commands
 ├── Multiplayer sync
 └── World/Server configuration

Genesis Content Packs
 ├── Base rig assets and modules
 ├── Species / hybrid definitions
 ├── Textures / animation profiles
 ├── Items / structures / sounds
 ├── Abilities / combat profiles
 └── Localization
```

**Required architectural invariant (ADR-001):** these layers have no dependencies on external mods. `Genesis Public API` is the versioned public boundary; optional, separately distributed adapters provide integrations. Genesis must never modify every NPC by default, and each Genesis-controlled NPC has exactly one primary controller (Section 24).

Specific classes, packages, and the availability of Hytale APIs must be **verified against the current server version** before implementing the adapter. Assumptions about dynamic model assembly must not be treated as confirmed engine features.

### 18.2. Proposed Services

| Service | Responsibility |
|---|---|
| `GenomeEngine` | Alleles, inheritance, de novo mutations |
| `CompatibilityService` | Family, rig, eligibility, pairing restrictions |
| `PhenotypeResolver` | Gene expression and attribute values |
| `MorphologyResolver` | Selection of compatible visual modules |
| `BreedingService` | Attempts, RNG, readiness, transactional result creation |
| `SparkRepository` | Storing Sparks without NPC entities |
| `CreatureRegistry` | UUIDs, instances, owners, life stages |
| `LineageService` | Ancestor graphs, provenance, kinship coefficients |
| `DiscoveryRegistry` | First manifestations, authors, breeds |
| `GenesisCodeCodec` | Deterministic serialization and validation |
| `CompanionAI` | Commands, following, combat roles |
| `BreedingUI` | Breeding and analysis interfaces |

### 18.3. Example Genome Persistence Record

```json
{
  "schemaVersion": 1,
  "rulesetId": "genesis-rules-1",
  "familyId": "canids",
  "hybridArchetypeId": "wolf_fox",
  "genome": {
    "FRAME": ["normal", "large"],
    "HEAD": ["wolf", "fox"],
    "LIMBS": ["normal", "runner"],
    "APPENDAGE": ["wolf_tail", "bushy_tail"],
    "PIGMENT": ["dark", "shadow"],
    "COVER": ["dense_fur", "short_fur"],
    "MUSCLE": ["strong", "normal"],
    "STAMINA": ["normal", "high"],
    "METABOLISM": ["normal", "fast"],
    "SENSE": ["keen_smell", "night_vision"],
    "TEMPER": ["loyal", "cautious"],
    "ARCANA": ["dormant", "shadow_affinity"]
  },
  "cosmeticVariantSeed": "0000000000000042"
}
```

Every permitted allele pair must have an expression rule. Ancestry, `creatureId`, current age, owner ID, and training are stored **in a separate document or component**.

### 18.4. Breeding Event

```json
{
  "breedingEventId": "uuid",
  "parentAId": "uuid-a",
  "parentBId": "uuid-b",
  "initiatorPlayerId": "player-uuid",
  "generationSeed": "rng-seed",
  "rngVersion": "genesis-prng-v1",
  "rulesetId": "genesis-rules-1",
  "resultSparkId": "uuid-spark",
  "status": "succeeded"
}
```

The event must be persisted atomically with consumption of the attempt and creation of the Spark. Repeating a request with the same `breedingEventId` must return the same result rather than creating an additional Spark.

### 18.5. Core API (Proposed Contract, Not a Finished Mod)

```java
public interface BreedingEngine {
    BreedingResult breed(
        CreatureGenome parentA,
        CreatureGenome parentB,
        BreedingContext context,
        RandomGenerator random
    );
}

public interface PhenotypeResolver {
    Phenotype resolve(CreatureGenome genome, GeneticRules rules);
}

public interface MorphologyResolver {
    Morphology resolve(CreatureGenome genome, MorphologyRegistry registry);
}
```

The RNG is injected externally, and algorithm versions are fixed. The genetics core can be tested without starting a game server.

### 18.6. Persistence and Reliability

- The server is the sole source of truth for births, genomes, Sparks, and discoveries.
- Stored data includes `schemaVersion` and supports migrations.
- Genotype and mutation outcomes are finalized before the UI receives a response.
- A new UUID is assigned to every manifestation or spawn.
- Owner information and pack-animal inventories persist through unloading, death, transfer, and server restarts.
- Breeding and discovery events are idempotent.
- Pets outside active regions do not require full combat-AI ticks.
- Storage and UI operations must prevent Spark duplication during lag or connection loss.

- Genesis components are never automatically attached to every vanilla NPC; one primary controller owns a Genesis creature lifecycle.
- Genesis does not erase foreign ECS components or overwrite foreign attribute modifiers.
- Unknown data from a disabled extension is retained losslessly; dependent operations are blocked until definitions are restored.
- Entity authority and integration contracts are defined by ADR-001 (Section 24).

---

## 19. Server Configuration

**Proposed schema**; default values are subject to testing:

```json
{
  "schemaVersion": 1,
  "taming": {
    "enabled": true,
    "minigameEnabled": true,
    "trustGainMultiplier": 1.0,
    "fearGainMultiplier": 1.0,
    "retainTrustOnFailure": 0.6,
    "allowQuickTamingAfterDiscovery": true
  },
  "breeding": {
    "enabled": true,
    "minigameEnabled": true,
    "manualMinigameBonusMax": 1.15,
    "automaticBreedingEnabled": true,
    "sameSpeciesCompatibility": 0.90,
    "sameFamilyCompatibility": 0.70,
    "stableBreedCompatibilityBonusEnabled": true,
    "cooldownMinutes": 10,
    "mutationChance": 0.08,
    "maxNewMutationsPerOffspring": 1,
    "sparksPerSuccess": 1,
    "sparkStorageInitial": 8,
    "sparkStorageUpgraded": 32,
    "sparkResourceRefund": 0.25
  },
  "inbreeding": {
    "mode": "soft",
    "trackAncestry": true,
    "maxTrackedGenerations": 4,
    "frailty": {
      "enabled": true,
      "threshold": 0.0625,
      "riskMultiplier": 0.2,
      "maxProbability": 0.15,
      "healthPenalty": 0.08
    },
    "restrictions": {
      "allowRelatedBreeding": true,
      "maxCoefficient": 0.5
    }
  },
  "genetics": {
    "allowCrossFamilyBreeding": false,
    "maxActiveInheritedAbilities": 1,
    "rulesetId": "genesis-rules-1"
  },
  "companions": {
    "maxActiveFollowers": 2,
    "maxActiveMounted": 1
  },
  "genesisCode": {
    "enabled": true,
    "playerExportEnabled": true,
    "playerImportEnabled": false,
    "adminSpawnEnabled": true
  },
  "compatibility": {
    "enabled": true,
    "entityOwnership": {
      "respectExternalOwnership": true,
      "allowAutomaticTakeover": false,
      "allowExplicitTransfer": false
    },
    "extensions": {
      "allowExternalSpecies": true,
      "allowExternalGenetics": true,
      "allowExternalAbilities": true
    },
    "conflicts": {
      "policy": "deny",
      "logConflicts": true,
      "warnAdministrators": true
    },
    "genesisCode": {
      "missingExtensions": "reject"
    }
  },
  "discovery": {
    "enabled": true,
    "trackFirstDiscoverer": true,
    "trackBreedFounders": true,
    "allowImportedDiscoveries": false,
    "allowAdminSpawnDiscoveries": false
  }
}
```

**Configuration validation:** all probabilities must be in `[0,1]`; storage capacities must have reasonable limits; cooldowns cannot be negative; configuration versions must support server-side migration; and the original ruleset must be retained for legacy genomes. When `inbreeding.mode = off`, the frailty module is disabled regardless of the nested `frailty.enabled` value (or configuration is normalized at load time). Strict mode requires separate server presets or explicit risk values.

---

## 20. Simulations, Testing, and Quality Criteria

### 20.1. Inheritance Tests

| Scenario | Expected result |
|---|---|
| `A/a × A/a` | 25% `A/A`, 50% `A/a`, 25% `a/a` |
| `A/A × a/a` | 100% `A/a` |
| Two independent recessive genes | 6.25% expression of both |
| Different families | Reject before consuming an attempt |
| New mutation | Approximately 8% on average |
| Same genotype and ruleset | Identical phenotype |
| Same Genesis Code | Identical genome and cosmetics |
| Spawn twice from one code | Different `creatureId` values |
| Full siblings with unrelated founders | `F_child = 0.25` |
| `inbreeding.mode=off` | No additional frailty |
| Any valid genotype | No unresolvable rig/module |
| Repeated network request | No duplicated Spark |
| Two simultaneous first discoveries | Exactly one winner for the unique key |

Large-scale frequency tests should generate 100,000+ synthetic offspring for fixed parent pairs, using tolerances based on statistical variation.

### 20.1.1. Compatibility Integration Tests

MVP verification must cover standalone startup, no mass takeover of vanilla NPCs, preserved genomes when an adapter is removed, preservation of external buffs and idempotent events, and safe failure for Genesis Codes requiring unknown extensions. The full test matrix and acceptance criteria appear in ADR-001, Section 24.9.

### 20.2. Playtests

- How long does it take a player to obtain their first pet and first hybrid?
- After automation is unlocked, do players still choose to play the manual minigame voluntarily?
- Do players understand the difference between pair compatibility and the probability of obtaining a desired genotype?
- How many Sparks are dispersed, how many are manifested, and why?
- Does the optimal strategy yield one dominant animal across all gameplay modes?
- How often do players quit because of bad luck or excessive repetition?
- Is the genetics UI manageable with dozens of breeding animals?
- Are modular anatomy assets stable when a server has many NPCs?

### 20.3. Balance Analysis

The simulator generates batches of genotypes, then compares combat effectiveness, biome traversal, resource-transport speed, and farm-material output while accounting for food and energy. **Pareto analysis** is needed: there should be no inexpensive, readily available genotype that outperforms all alternatives across every important role.

### 20.4. UX and Accessibility Verification

Test keyboard interaction, untimed actions, clarity of feedback, alternatives to color-only signaling, confirmation before dispersing rare Sparks, and readability of hidden-trait information.

---

## 21. MVP, Implementation Phases, and Risks

### 21.1. Minimum Playable MVP

**Scope:** one family (Fangkin), two original species (wolf and fox), one hybrid archetype, 12 basic loci with a small allele set, several beneficial and harmful mutations, taming, a short breeding minigame, Sparks, the Genesis Cradle, maturation, a basic companion, Genesis Code, persistence, and server-side inbreeding configuration.

**Required MVP compatibility (ADR-001):** standalone startup, explicit NPC authority, no global takeover or writes to foreign components, idempotent breeding, and preservation of unresolved extension data. Full third-party species APIs and separately installed adapters are not required for the MVP.

**Exclude from the early MVP:** all four fully developed families, every production lineage, unique magical animations, markets, cross-family hybrids, and global Chronicles.

### 21.2. Implementation Sequence

| Phase | Work | Definition of done |
|---|---|---|
| **0. Technical spike** | Test assembly of heads/tails/textures and NPC synchronization in Hytale | Working visual prototype or validated fallback |
| **1. Genesis Core** | Inheritance, mutations, loci, expression, codes, tests | Deterministic calculations and test vectors |
| **2. Vertical gameplay slice** | Wolf/fox, taming, enclosure, Spark, manifestation | Complete loop from wild animal to hybrid |
| **3. Selective breeding** | Laboratory, pedigrees, breeds, inbreeding, recommendations | Players can deliberately breed for a combination |
| **4. Roles** | Mounts, pack animals, production, scouting, abilities | Roles are useful and economically distinct |
| **5. Social layer** | Chronicles, attribution, trading, external calculator | Discovery history is verifiable and portable under specified rules |

### 21.3. Key Risks

| Risk | Consequence | Mitigation |
|---|---|---|
| Hytale limitations on dynamic anatomy | Arbitrary hybrid assembly is impossible | Early technical spike; complete base models + attachments |
| Excessive combinatorial complexity | Insufficient assets, visual mismatches | Family rigs, permitted-module tables, asset validation |
| Grinding for rare genes | Players abandon the mod | Sparks, forecasts, automation, wild carriers |
| Mass-breeding exploits | Lag, uncontrolled resource generation | Server limits, cooldowns, atomic operations, costs |
| Unexplained bad outcomes | Frustration | Display known risks, journal, analysis tools |
| Incompatible code versions | Loss of creature identity | Versioned rulesets, migrations, explicit rejection |
| Forged first discoveries | Loss of trust in Chronicles | Server-side events, no rewards for admin spawning |
| One universally overpowered combination | Loss of meaningful species choices | Physiological costs, multi-scenario balance tests |
| Conflict with another mod on the same NPC | Competing AI, models, inventory, or saves | Explicit authority, no automatic takeover, validation of known conflicts (ADR-001) |
| External genetic extension is removed | Lost data or non-reproducible Genesis Code | Opaque storage of unknown payloads and safe failure for dependent actions |

---

## 22. Complete Example Gameplay Scenario

**Player objective:** breed a fast, nocturnal Wolfox as a combat companion.

1. **Search.** The player finds a forest wolf, observes its territorial behavior, learns its preferred food, and tames it through the trust minigame.
2. **Second parent.** In another area, the player finds a fox carrying a hidden shadow-color allele and studies it in the laboratory.
3. **Enclosure.** The player assigns the pair, prepares the environment, and completes three rounds of “Harmony.” A successful round raises the chance of a successful attempt but does not “select” the shadow allele.
4. **Spark.** A genetic sample appears. Analysis reveals an interesting coat color but weak joints. The player disperses the Spark; no physical animal was ever created.
5. **Another attempt.** After the pair recovers, a Spark with good speed and the desired carrier status is produced and stored as material for the next generation.
6. **Fixing the traits.** Over time, the player selects breeding stock with better allele combinations, increasing the proportion of desired offspring according to ordinary inheritance rules.
7. **Manifestation.** The player manifests the best Spark as a young animal, observes its changing appearance as it grows, teaches it commands, and assigns the “Hunter” role.
8. **Expedition.** The companion's night perception proves useful, but the player must account for its low armor and energy consumption.
9. **Legacy.** The player saves its Genesis Code, registers the breed when the criteria are met, and receives a Chronicles entry if this is the first manifestation of the rare form on the server.

**Outcome:** the loop combines exploration, a behavior-based minigame, probabilistic breeding, deliberate choices, and a long-term collection objective without forcing the player to maintain dozens of unwanted NPCs.

---

## 23. Decision Log and Open Questions

### 23.1. Established Concept Decisions

- Name: **Genesis**.
- Fantasy hybrids within compatible families.
- Moderately complex genetics (12 independent diploid loci for the MVP).
- Modular morphology with constrained, validated rigs.
- Harmful mutations and meaningful physiological trade-offs.
- Taming through observation and interaction.
- Breeding through enclosures and a minigame that can later be skipped.
- Life Sparks instead of mandatory physical births for every genome.
- Relatedness with `off / soft / strict` server modes.
- Genesis Code, separate instance UUIDs, and versioned-ruleset determinism.
- Server-local discovery history and first-discoverer credit.
- Equally supported combat, travel, farming, and collection gameplay.
- Genesis is fully standalone and requires no other mod at runtime (ADR-001).
- NPCs have explicit primary control; Genesis never automatically takes over external creatures or globally changes every animal.
- Extensions use a namespaced, versioned public API or optional, separately installed adapters.
- Known conflicts fail per operation without corrupting external data; unknown genetic payloads are retained.

### 23.2. Initial Parameters — **Not Final Balance Values**

- 90%/70%/0% baseline compatibility.
- 8% new mutations (4% cosmetic; 2.5% physiological; 1% behavioral; 0.5% anomalous).
- 10-minute cooldown between attempts.
- 8/32 Spark storage slots.
- 25% refund of common consumables upon dispersal.
- Two companions and one active mount.
- Breed registration: 3 generations / 8 evaluated offspring / 75% expression.
- Attribute numbers and role restrictions from Section 11.

### 23.3. Questions Requiring Decisions or Verification

1. **Hytale model spike:** which NPC parts can actually be replaced dynamically, and how do those changes interact with animations and collisions?
2. **Sparks:** a physical item, block-based container, server registry, or hybrid design? How can duplication during transfers be prevented?
3. **Enclosure recipes:** complexity and costs for different progression tiers.
4. **Minigame precision and duration:** how many actions are optimal, and when should automatic mode unlock?
5. **Concrete allele sets:** pair-expression tables for all 12 loci in each family.
6. **Mutation combinations:** eligibility policies for new alleles and compatibility among arcane effects.
7. **Pedigrees:** whether to store the complete graph or archive older generations, and how to account for unknown ancestors.
8. **Pets after death:** revival, injuries, or permanent loss; a server setting is needed.
9. **Markets/trading:** should full codes of genetically valuable animals remain private?
10. **Chronicles:** the exact definition of a “rare form,” naming rules, and authorship when a Spark is transferred.
11. **Determinism across servers:** a uniform ruleset/model set or a compatibility mode with explicit warnings.
12. **Monetization/distribution (future):** licensing, dependency requirements, and Hytale asset usage rules.
13. **Integration spike:** discover external primary controllers, support source-aware attribute modifiers, and verify Hytale ECS and event lifecycle boundaries.
14. **Public API:** decide which minimal contracts should be stable in v1 and which remain experimental.
15. **Cross-mod NPC transfers:** handshakes and migration are outside the MVP and require cooperative APIs.

---

## 24. ADR-001 — Standalone Architecture and Mod Interoperability

> **Status:** accepted · **Date:** October 15, 2026 · **Scope:** architecture, data, NPCs, public integrations  
> **Priority:** core safeguards are required for the MVP; advanced third-party APIs may be delivered incrementally.

### 24.1. Context and Decision

A server may run several mods offering taming, companions, attributes, models, or new species. If they all unconditionally take control of the same NPCs, their AI, persistence, animation, and ownership systems can conflict.

**Decision:** Genesis must remain fully functional **without mandatory dependencies on other mods**. All essential gameplay systems (taming, breeding, genetics, mutations, modular morphology, Life Sparks, commands, storage, and Genesis Code) are implemented by Genesis and the Hytale API. No third-party mod is required for Genesis to start.

Two goals must be distinguished:

- **Compatibility:** Genesis does not claim externally managed NPCs, corrupt external state, or unnecessarily interfere with installed mods.
- **Interoperability:** a mod deliberately using the public Genesis API, or a separately installed adapter, can contribute species, genetics, abilities, and agreed integration behavior.

Compatibility safeguards are mandatory for the MVP. Broad interoperability is an architectural contract that may be implemented in phases. **Conflict-free operation with every arbitrary mod cannot be guaranteed:** another mod might override global NPC AI or model components without cooperation.

### 24.2. Dependency Direction and Module Boundaries

```text
Genesis Core (pure Java; no Hytale or third-party mod dependency)
    ↑
Genesis Public API (versioned contracts, immutable DTOs, extension points)
    ↑
Genesis Hytale / Gameplay (server authority, persistence, UI, NPC control)

Third-party compatibility adapter (optional and separately deployed)
    ├── depends on Genesis Public API
    └── depends on the external mod API, if required
```

These are logical boundaries, not a requirement for multiple distributable JARs: Genesis may ship as one standalone mod. The public API must not expose writable internal state. The main Genesis manifest **does not refer** to named external mods; a standalone adapter declares any dependencies it needs. Installing or removing an adapter must not affect Genesis startup.

**Dependency rule:** `genesis-core` imports neither Hytale nor other mods. `genesis-hytale` does not import Animal Husbandry, Tamework, or any other external mod. Adapters depend on Genesis, never the reverse.

### 24.3. Authority: Entity Ownership and Subsystem Control

`GenesisCreatureComponent` explicitly marks an entity managed by Genesis. It is attached **only** to creatures created by Genesis or accepted through a successfully negotiated transfer. It must never be added wholesale to all vanilla animals while loading worlds or scanning chunks.

Proposed components (conceptual names; the actual ECS contracts require verification against Hytale):

```text
GenesisCreatureComponent       — identity + schema version + primary controller
GenesisGenomeComponent         — inherited genome and ruleset
GenesisOwnershipComponent      — player ownership, permissions, provenance
GenesisMorphologyComponent     — rig, visual modules, appearance variants
GenesisCompanionComponent      — commands, role, training
GenesisExtensionDataComponent  — persisted registered extension data
```

An NPC has **one primary controller** responsible for taming, behavior control, reproduction, and authoritative state. Additional effects (external buffs, cosmetic overlays) may coexist when exposed through compatible, explicitly supported interfaces.

| Situation | Genesis behavior |
|---|---|
| Wild vanilla NPC with no known controller | Do not alter it automatically; start Genesis taming only through an explicit player action |
| NPC with `GenesisCreatureComponent` | Genesis controls it; extensions act only through authorized contracts |
| NPC known to be managed by another mod | Do not take over; reject conflicting operations |
| NPC species introduced by another mod | No automatic import; registration or an adapter is required |
| Ownership or control cannot be established reliably | Do not assume control blindly; reject potentially conflicting operations or require an explicitly supported path |
| Transfer of control | Only through an explicit adapter handshake, authorization checks, and an atomic ownership change |

`playerOwnerId` (the in-game owner) is distinct from `primaryControllerId` (the controlling mod or subsystem). Reading another mod's metadata does not grant authority. There is no universal method to detect ownership by an unknown mod; detection depends on supported signals and APIs.

### 24.4. Write Rules and Conflict Resolution

| Subsystem | Source of truth and write boundary |
|---|---|
| Genome / inheritance | Genesis Core and `BreedingService`; extensions register definitions before ruleset freeze |
| Breeding | Only the designated controller commits breeding results and Life Sparks; requests are idempotent |
| AI / navigation / commands | One primary behavior controller, with no competing AI loops |
| Base model, rig, hitbox | One primary anatomy controller; cosmetic overlays only through compatible slots |
| Attributes / effects | Independent modifiers with namespaced source IDs, not constant overwrites of shared values |
| Inventory / items | One authoritative container exposed through controlled operations |
| Persistence | Genesis writes only Genesis-owned data; it does not erase or migrate foreign components |
| Player ownership | Only the Genesis Ownership service may update the Genesis ownership record |

For attributes, prefer namespaced source modifiers such as `genesis:genetic_strength` and `othermod:equipment_bonus`. Reloads, repeated events, and modifier removal must be idempotent. If Hytale provides no source-aware attribute mechanism, implement a reconciler or disable that unsupported integration. Never set an absolute `strength=70` every tick in a way that erases external buffs.

Creature transfer between independent mods is **outside the MVP**. If introduced later, it requires a handshake: validate both sides and permissions → freeze mutable actions → create a transferable snapshot → obtain target acceptance → atomically switch the controller → release old resources; failures must roll back without duplicating entities or inventories.

### 24.5. Public Extension API

Internal implementation packages are not a supported public API. A separate `GenesisApi` exposes read-only creature/genome views and restricted service operations. Content IDs use namespaces such as `genesis:wolf` and `examplemod:dragon`; registered IDs must not be overwritten without explicit authority and a versioned migration.

| Contract | Third-party capability |
|---|---|
| `CreatureProvider` | Register a compatible species or hybrid archetype |
| `GeneticsExtension` | Register loci, alleles, expression rules, and migrations |
| `MorphologyProvider` | Register validated rig modules and assets |
| `AbilityProvider` | Register abilities with declared prerequisites, costs, and cooldowns |
| `CompatibilityPolicy` | Approve or reject operations at supported validation points |
| `GenesisEvents` | Observe documented creature lifecycle events |

These are **proposed Genesis contracts**, not existing Hytale classes:

```java
public interface GenesisApi {
    CreatureViewService creatures();
    GeneticDefinitionRegistry genetics();
    MorphologyDefinitionRegistry morphology();
    BreedingOperations breeding();
    GenesisEvents events();
}

public interface CreatureProvider {
    String namespace();
    Collection<CreatureDefinition> definitions();
}
```

External access to creatures uses immutable snapshots. Genome modification is allowed only through specifically supported creation/mutation workflows, with validation and versioning. Critical definitions are frozen after content registration and before any gameplay calculations (**registry freeze**). Hot-swapping genetics rules without migration is forbidden.

The public API is versioned independently from internal implementation. Breaking changes require a new major API version, migrations, and an explicit compatibility policy.

### 24.6. Events and Operation Coordination

**Pre-commit (`Before*`)** events allow constrained validation and cancellation, not arbitrary genome mutation. **Post-commit (`After*`)** events provide immutable descriptions of completed operations.

Examples include `BeforeTamingEvent`, `BeforeBreedingEvent`, `BreedingCompletedEvent`, `LifeSparkCreatedEvent`, `CreatureManifestedEvent`, `CreatureReleasedEvent`, `MutationDiscoveredEvent`, and `SpeciesDiscoveredEvent`.

The breeding lifecycle is:

```text
request → identify controller → permission & ownership checks
        → validate parents & compatibility → BeforeBreeding validators
        → reserve resources / enforce idempotency
        → compute + atomically commit genome, event, and Spark
        → publish immutable post-commit events
```

External handlers never receive mutable genomes or rewrite a committed result. A failing/timed-out mandatory pre-validation step must reject safely, without consuming an attempt. A failed post-commit listener must not roll back a birth or trigger regeneration; reliable consumers use event IDs, idempotency, and an outbox/retry mechanism when needed. Adapter calls must not block a server tick indefinitely.

### 24.7. Genesis Code, External Data, and Missing Extensions

Genesis Code and server genomes may contain **only** registered namespaced alleles and versioned extensions. An extension record includes `extensionId`, `schemaVersion`, and a deterministic serializable payload. Canonical ordering and byte encoding must be specified, or equivalent genomes may yield different codes.

When a required extension is absent:

1. Do not delete or silently replace unknown genes or extension payloads in persisted data.
2. Preserve original bytes/metadata so they can be restored if the extension returns.
3. Reject breeding, mutation, import, or spawning if required definitions cannot be resolved safely.
4. Default to `missingExtensions = reject` for exact reproduction; cosmetic fallback is allowed only for explicitly optional visuals and must not change the genome.
5. Imported/admin-spawned creatures do not qualify for first-discovery credit by default (Section 16).

An external extension cannot write into `genesis:*` identifiers without authorization. A checksum detects accidental corruption; it is **not** a trusted-server signature or proof of provenance.

### 24.8. Configuration and Deployment Policy

The main Genesis configuration includes `compatibility` (Section 19). Default behavior uses `respectExternalOwnership = true`, `allowAutomaticTakeover = false`, `allowExplicitTransfer = false`, `conflicts.policy = deny`, and `genesisCode.missingExtensions = reject`.

Compatibility adapters are installed **separately**. Server administrators can disable extensions and integrations without disabling the Genesis gameplay loop. When an extension is disabled, existing data must remain preserved as unresolved/opaque until an explicit migration is performed.

The `deny` policy rejects an individual conflicting operation; it **does not disable the entire Genesis mod**. Conflicts should be logged in a structured form containing `entityId`, `operation`, `otherController` (if known), `reason`, and `decision`; minimize personal data in logs.

### 24.9. Tests and Acceptance Criteria for ADR-001

| Scenario | Required outcome |
|---|---|
| Genesis is the only mod installed | Full base gameplay works; no missing external mod classes |
| Another taming mod is installed | Genesis does not take over a known externally controlled pet |
| A vanilla NPC spawns | Genesis does not modify it automatically without player interaction |
| Two mods attempt NPC control | Known conflicts reject the second primary controller |
| An external source adds an attribute buff | Genesis modifiers neither erase it nor duplicate after reload |
| A compatibility adapter is removed | Genesis starts; Genesis data and pets remain intact |
| An extension introducing a gene is removed | Its payload is preserved; dependent operations fail with a clear error |
| A code with an unknown `examplemod:*` gene is imported | No partial spawn and no silent fallback of genetic data |
| A breeding request is retried | One transaction, one Spark, one set of result events |
| An external event listener throws | Committed offspring is neither lost nor regenerated |
| Two players trigger a first discovery | The unique discovery claim is atomically assigned once |
| All integrations are disabled | Taming, genetics, code spawning, and persistence still work |

**MVP acceptance:** Genesis starts and functions independently, never performs blanket changes to all NPCs, tracks authority over its own pets, commits breeding idempotently, and does not corrupt unresolved external genetic data. Full third-party species registration and adapters may follow later.

### 24.10. Technical Spike Risks and Open Questions

- Which Hytale or third-party signals can identify an NPC's primary controller? If none are reliable, use a conservative policy; do not promise universal detection.
- Does the current server API support source-aware attribute modifiers? If not, use a reconciliation layer or disable that integration.
- How can event lifecycle, atomic transactions, and ECS persistence be coordinated without duplication?
- Can cosmetic modules be attached dynamically without overwriting external models or animations?
- What public API surface must be stable in the initial release, and which contracts remain experimental?

**ADR-001 outcome:** Genesis does not require another mod's implementation or runtime. It exposes **controlled, optional** extension points and handles known conflicts defensively. Interoperability must never compromise its independent operation.

---

## Conclusion

Genesis is built around four successive sources of player enjoyment:

1. **Understand and tame** an interesting creature.
2. **Plan and obtain** a new combination of inherited traits.
3. **Select, raise, and use** an animal that genuinely matters.
4. **Leave a legacy** in the history of breeds and discoveries on the player's server.

Life Sparks address the central problem of large-scale random breeding. Genesis Code provides reproducibility and a foundation for web tools. Modular anatomy makes heredity visible, while server settings let administrators tailor difficulty for single-player worlds and public servers.

**The first technical priority is to verify modular anatomy capabilities in the current Hytale version; the first gameplay priority is a vertical slice: “wolf + fox → minigame → Spark → manifested hybrid.”**
