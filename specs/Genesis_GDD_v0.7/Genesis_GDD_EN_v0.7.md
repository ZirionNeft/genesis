# Genesis — concise Game Design & Technical Specification v0.7

**Purpose:** one implementation contract for a standalone Hytale mod. This edition retains only gameplay decisions, numeric rules and engineering invariants. Stage history, repeated explanations and test reports are removed. Attached JSON registries contain the complete normative tables. Numeric values are initial playtest targets, not measured Hytale NPC stats.

## 1. The game

Observe an **existing vanilla animal** → tame it → research inherited traits → choose parents → play Harmony or use automation → receive **one Life Spark** with a fixed genome → inspect, store or disperse it → manifest an infant → care, train and choose an adult role. The aim is meaningful choices and discovery, **not mandatory mutation grinding**.

Genesis is standalone: no required mods, no global takeover of other NPCs and no cloned vanilla founders. Optional integration uses a versioned public API. Death and difficulty are configurable per server. Extra inbreeding penalties are not included (pedigrees are retained).

## 2. Terms and invariants

- A **gene** is an inherited trait with two **variants**, one from each parent. The **genetic makeup** is the collection of these pairs; **expression** determines observable traits and potential.
- A **nonexpressed** variant and an **undiscovered** variant are different states. Carriers of different recessive variants may look alike; **distinct expressed morphology must remain visibly distinguishable**.
- A **founder species** is a candidate vanilla animal; **historical form** records lineage (e.g., wolf–fox); **effective genetic form** reflects inherited founder markers; a **breed** is a registered player line.
- A **Life Spark** is an unmanifested future companion, never currency or a live NPC. A **Genesis Code** describes genetics but grants no survival-mode spawn entitlement.
- Genome, pedigree and `creatureId` remain stable. XP, age, current condition, owner and cargo are separate data. Minigames, training and revival never rewrite inherited genes.
- The server authorizes each meaningful action; retries are idempotent and cannot duplicate Sparks, companions or inventory.

## 3. Founders and hybrid graph

**Four families × seven forms = 28**: three founders (`A,B,C`), three pairs (`AB,AC,BC`) and a three-way hybrid (`ABC`). Stable IDs and names are listed below. Binding to a vanilla NPC requires verification of its real Role ID, model, animations and rig. Missing vanilla founders remain unavailable; **do not fabricate replacements**.

| Family | A | B | C | AB | AC | BC | ABC |
|---|---|---|---|---|---|---|---|
| `canids` | **Wolf** (`wolf`) | **Fox** (`fox`) | **Hyena** (`hyena`) | **Wolfox** (`wolf_fox`) | **Wolfhyena** (`wolf_hyena`) | **Foxhyena** (`fox_hyena`) | **Trifang** (`canid_triad`) |
| `runners` | **Horse** (`horse`) | **Deer** (`deer`) | **Antelope** (`antelope`) | **Manehorn** (`horse_deer`) | **Stepestrider** (`horse_antelope`) | **Windhoof** (`deer_antelope`) | **Tristrider** (`runner_triad`) |
| `heavies` | **Bison** (`bison`) | **Boar** (`boar`) | **Mosshorn** (`mosshorn`) | **Tuskbison** (`bison_boar`) | **Ironback** (`bison_mosshorn`) | **Mosstusk** (`boar_mosshorn`) | **Thunderback** (`heavy_triad`) |
| `avians` | **Raven** (`raven`) | **Owl** (`owl`) | **Hawk** (`hawk`) | **Moonwing** (`raven_owl`) | **Shadowflight** (`raven_hawk`) | **Duskwing** (`owl_hawk`) | **Starwing** (`avian_triad`) |

**Breeding:** within-family only. Historical founder sets merge: `roots(child) = roots(A) ∪ roots(B)` and resolve to a graph node. Examples: `AB×A→AB`, `AB×C→ABC`, `ABC×A→ABC`. This records **lineage**, not genetic share; backcrossing can restore founder-like physiology without erasing hybrid history. Cross-family breeding is forbidden. Future graph additions use new immutable IDs without reinterpreting existing forms or codes.

The complete symmetric matrix and all 112 unique allowed combinations are defined by `Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json`.

## 4. Genetics and phenotype calculation

**Only supported genetic baseline:** `geneSchema v2`, `expressionRules v2`, `morphologySet v2`; `hybridGraph v1` and `GEN1` are independent protocol versions. No genetic v1 support. A genome has 23 common genes, family-specific genes and three diploid `HERITAGE_1..3` markers (six founder copies): **26 pairs** for canids, **27** for runners/heavies and **28** for avians.

For every gene, a child independently receives one copy from each parent; a new mutation is applied only after ordinary inheritance. The six `HERITAGE` copies control the founder-species contribution to physiology. Historical form **never determines base stats**.

**Deterministic stat rule for `x`:**

```text
Base[x] = Σfounder (copies[founder] / 6) × founderBase[x]
Multiplier[x] = clamp(1 + Σgene percentDelta[x], 0.75, 1.35)
Stat[x] = roundHalfUp(max(minStat[x], Base[x] × Multiplier[x] + Σgene flatDelta[x]))
```

HP, damage, armor, stamina, cargo slots and sense radius are integers; speed is rounded to two decimals. Example: wolf (120 HP, 5.30 speed) × fox (80 HP, 6.00 speed), 3:3 founder copies and otherwise neutral variants → **100 HP, 5.65 blocks/s**. Age, training, role, conditions and gear apply **after** genetic potential.

| Gene | One special copy | Two special copies |
|---|---|---|
| `BODY_FRAME` | HP +8%, speed −3% | HP +16%, speed −6% |
| `LIMB_FORM` | speed +2%, HP −2% | speed +4%, HP −4% |
| `MUSCLE` | attack +5%, attack stamina cost +3% | attack +10%, cost +6% |
| `BONE_STRENGTH` | armor +1, speed −2% | armor +2, speed −4% |
| `ENDURANCE` | stamina +8 | +16 |
| `SENSES` | sense +2 blocks | +4 blocks |
| `COAT_DENSITY` | cold damage −5%, heat stamina cost +3% | −10%, +6% |

Dominant, recessive and intermediate expression is explicit. Spotted patterns and glowing eyes require two expressed variants; carriers can be visually indistinguishable. The complete pair tables and validation live in `Genesis_GDD_v0.7_Gene_Schema_v2.json` and `Genesis_v0.7_Stage_10_Expression_Rules_v2.json`.

**Visuals:** genome-derived body/head/ears/tail/limbs/coat/pigment/horns/wings/beak states select age-compatible model bindings. Missing mandatory genes, rules or rig/part assets fail closed; never substitute an approximate animal. Hytale model/animation compatibility still requires integration testing.

## 5. Concrete starting content

These are **neutral level-1 adult reference values**. Hybrid values represent illustrative balanced `HERITAGE` copies, never form-based fixed stats. All seven stats (HP, attack, armor, speed, stamina, cargo, senses), allowed roles, 21 mutation types and eight missions are normative in `Genesis_v0.7_Stage_08B_Content_Catalog_v1.json`.

| Form | HP | Attack | Armor | Speed, b/s |
|---|---:|---:|---:|---:|
| Wolf (`wolf`) | 120 | 16 | 7 | 5.30 |
| Fox (`fox`) | 80 | 11 | 3 | 6.00 |
| Hyena (`hyena`) | 135 | 18 | 6 | 4.80 |
| Horse (`horse`) | 155 | 9 | 7 | 7.50 |
| Deer (`deer`) | 115 | 10 | 4 | 6.70 |
| Antelope (`antelope`) | 95 | 9 | 3 | 8.00 |
| Bison (`bison`) | 220 | 20 | 12 | 4.10 |
| Boar (`boar`) | 165 | 18 | 9 | 4.80 |
| Mosshorn (`mosshorn`) | 245 | 16 | 15 | 3.50 |
| Raven (`raven`) | 65 | 8 | 2 | 7.00 |
| Owl (`owl`) | 75 | 10 | 3 | 6.30 |
| Hawk (`hawk`) | 70 | 12 | 2 | 8.10 |
| Wolfox (`wolf_fox`) | 100 | 14 | 5 | 5.65 |
| Wolfhyena (`wolf_hyena`) | 128 | 17 | 7 | 5.05 |
| Foxhyena (`fox_hyena`) | 108 | 15 | 5 | 5.40 |
| Trifang (`canid_triad`) | 112 | 15 | 5 | 5.37 |
| Manehorn (`horse_deer`) | 135 | 10 | 6 | 7.10 |
| Stepestrider (`horse_antelope`) | 125 | 9 | 5 | 7.75 |
| Windhoof (`deer_antelope`) | 105 | 10 | 4 | 7.35 |
| Tristrider (`runner_triad`) | 122 | 9 | 5 | 7.40 |
| Tuskbison (`bison_boar`) | 193 | 19 | 11 | 4.45 |
| Ironback (`bison_mosshorn`) | 233 | 18 | 14 | 3.80 |
| Mosstusk (`boar_mosshorn`) | 205 | 17 | 12 | 4.15 |
| Thunderback (`heavy_triad`) | 210 | 18 | 12 | 4.13 |
| Moonwing (`raven_owl`) | 70 | 9 | 3 | 6.65 |
| Shadowflight (`raven_hawk`) | 68 | 10 | 2 | 7.55 |
| Duskwing (`owl_hawk`) | 73 | 11 | 3 | 7.20 |
| Starwing (`avian_triad`) | 70 | 10 | 2 | 7.13 |

**21 mutation types:** six cosmetic, six positive physiological, four positive behavioral, one magical awakening and four adverse. Each targets a specific gene (pigment, pattern, eyes, tail, ears, coat, muscles, bones, endurance, healing, metabolism, senses, temperament, training, production or arcana). A new variant is not necessarily expressed; expression follows the inherited pair. Positive traits may have trade-offs.

## 6. Taming, breeding and magical progression

**Vanilla NPC adoption:** first voluntary observation stores a separate fixed-genome `WildCandidateRecord` without modifying or copying the animal. Successful taming safely attaches Genesis ownership to **the same NPC**. Check permission, founder availability and safe takeover before committing; otherwise decline without cloning.

Starting values: trust **20/100**, fear **15/100**; four ordinary successes may reach the 100 trust threshold. Fear at 100 ends contact. Quick taming unlocks after three successful tamings of a species. Reaction timers are optional.

**Breeding:** requires eligible adult parents, proper conditions and a reserved Cradle slot. Harmony has three brief untimed rounds (environment, contact, resonance). Automation unlocks after three successful family breedings, or immediately when the minigame is disabled.

```text
C = 0.90 (same form), 0.70 (different forms in same family), otherwise 0
P(success) = min(0.95, C × readinessA × readinessB × environment × Q)
environment ∈ [0.8, 1.1]
Qauto = 1.00; Qmanual = 0.90 + 0.25 × score/100
```

Manual `score ≥ 80` also reduces the next parental cooldown by **10%**, even when success probability is capped at 95%. Base cooldown **10 min**, minimum 5 min. Harmony affects **success and cooldown only**, never inherited variants or mutation probabilities. The server commits one deterministic attempt and `generationSeed`; reconnects and UI restarts cannot reroll it.

**New mutation chances per successful eligible offspring:** cosmetic **5%**, physiological **4.5%**, behavioral **2.5%**; magical **6% / 12% / 22%** depending on family research tier. At most one new mutation event per offspring. Tiers require varied wild observations, routes, hybrid discovery and gene comparison, not repetitive breeding. Focused mastery guarantees the **first** magical discovery per player/family within **7 eligible successful births**; it neither selects a specific spell nor modifies ordinary Mendelian inheritance. Other routes are finding wild arcane lineages (tentative 3% / 12% by environment) and learning nonheritable role abilities.

## 7. Life Sparks and Genesis Code

Successful breeding creates **exactly one** Spark with fixed genetics and ancestry, not a live NPC. Initial Cradle capacity is **16**, upgraded **48**. Players can inspect discovered traits, compare, retain, filter, disperse without material refund or manifest an infant. **Unknown traits are never automatically discarded by default.** Manifestation consumes one Spark exactly once.

**Two formats:** a public passport reveals no hidden variants; the full inheritable code is controlled by `genesisCode.visibility`: `always` or `after_analysis` (default). Export is not a free survival spawn entitlement.

```text
GEN1.BASE64URL_NO_PADDING(UTF8(RFC8785_CANONICAL_JSON)).CRC32_HEX8
```

The code references the exact form, gene pairs, visual seed, extensions and immutable registry IDs/versions/SHA-256 hashes. CRC32 catches accidental corruption, **not forged provenance**. Current XP, ownership, gear, life state and revival permission are excluded. Import validates manifests, genes, assets and server authorization; missing *critical* extensions fail closed without side effects. `GEN1` is a transport version, not genetic v1.

## 8. Growth, XP, roles and abilities

**Stages:** infant (stationary nursery presentation, satiety/comfort/rest, ~**15 min**), juvenile (mobile companion and training, **45 min**), adult (full roles, breeding, combat/riding), veteran (earned mastery, not biological aging). XP never bypasses age gates and is not inherited. Level cap: **30**.

```text
Δgrowth = Δminutes / stageDuration × welfareMultiplier × (1 + min(0.25, 0.05 × uniqueCareMilestones))
XP_to_next(level) = 80 + 18×(level−1) + 3×max(0, level−10)^2
XP_total(level30) = 17038
```

Care bonuses apply only to **future** elapsed time. Offline growth is capped at **240 min** per absence, with no passive XP or unrepeatable rewards; missed lessons remain available later. Validated XP events are idempotent. Repeating one context in 20 min has novelty multipliers **1 / 0.4 / 0.1 / 0** without penalizing real diverse activity. Veteran requires level ≥25, bond ≥75 and achievements; mentoring grants at most +10% XP without stacking.

**Roles:** an adult chooses an anatomy/genetics-eligible role and can retrain without losing total XP. Follow/Stay/ReturnHome never randomly fail due to bond. Role skills unlock at **12 / 20 / 28** with two active role ability slots. Full effect definitions are in the JSON catalog.

| Role | Main purpose | Abilities at 12 / 20 / 28 |
|---|---|---|
| Guardian (`guard`) | Intercepts a threat within 10 blocks; never aggros neutrals | Shield: −20% ally damage for 5s, 35s CD / Hold: 25% slow for 3s, 40s CD / Guard ring: 5-block radius, −10% group damage for 6s, 70s CD |
| Hunter (`hunter`) | Targeted commanded attack; no automatic neutral hunting | Precise strike: +15% for one hit, 20s CD / Pursuit: +15% speed for 5s, 35s CD / Coordinated strike: +10% player damage to marked target for 5s, 75s CD |
| Mount (`mount`) | Carries one rider on an eligible adult rig; requires saddle | Smooth ride: −20% travel stamina drain / Sprint: +20% speed for 4s, 40s CD / Traverse: −30% terrain speed penalty |
| Hauler (`hauler`) | Cargo inventory based on body capacity | Packing: +2 slots if rig allows / Balanced load: −20% full-load stamina drain / Caravan: −15% route time out of combat |
| Scout (`scout`) | Marks engine-visible targets within senseRadius | Attention: +4 blocks scouting range / Trail: shows last seen target location for 10s, 30s CD / Overview: +8 radius for 8s, 90s CD |
| Producer (`producer`) | Produces 1 species-appropriate common resource per 20 min of active care, max 6/day | Gentle care: 18-min cycle / Harvest: +1 common resource on every third completed cycle / Comfort: preserves cycle across a pause up to 15min |
| Support (`support`) | Assistance: +5% ally stamina recovery within 6 blocks | Encourage: +15 ally stamina, 35s CD / Assist: −10% incoming damage to one ally for 5s, 45s CD / Harmony: 8s +10% group stamina regen within 6 blocks, 75s CD |

**Innate magic:** one active spell when `ARCANA` and a compatible `ELEMENT` are expressed; age/training unlock gates still apply. Noninherited role skills are a separate progression route.

| Element | Spell | Effect | Cooldown / stamina |
|---|---|---|---|
| `ember` | Ember burst | 6 magic damage to one target within 8 blocks | 20 s / 20 |
| `mist` | Mist veil | Reduces noncombat NPC detection radius by 30% for 5s; never hides from players | 35 s / 25 |
| `verdant` | Verdant pulse | Restores 16 HP to one ally; cannot revive or exceed max HP | 40 s / 30 |
| `storm` | Storm mark | 5 magic damage and 15% slow for 2s | 35 s / 25 |
| `stone` | Stone mantle | −15% damage taken by owner for 6s; does not stack with identical aura | 45 s / 30 |

## 9. Dismissal, summoning, death and revival

**Dismiss** is not death and consumes no revival allowance. **Summon** recreates the same companion projection with the same `creatureId`; at most one interactive NPC projection is valid at once. **Summoning in combat is disabled by default**: `summon.allowInCombat=false`, 10-second post-combat grace. Operators can change it. Existing companions remain active when combat starts; dismissal has its own rules. Check combat both before the durable transition and before the NPC becomes interactive.

**Server-selected death policies:**

| `deathPolicy` | After lethal damage | Recovery |
|---|---|---|
| `instant` (default) | Companion returns alive to reserve | Free, normal summon |
| `revival_station` | Awaits an altar or allowed world point | Free station interaction |
| `permadeath` | Final death | None |

**No Life Charges, Spark payments or other revival currency.** `maxRevives=null` means unlimited successful restorations, `0` means none, and positive integers set a quota. Increment **once on successful recovery**, never on dismissal or opening an altar. Exhaustion leads to final death. Operators set restored HP/stamina, temporary stat penalties, ability locks and summon protection. Genetics, XP, skills, bond, pedigree and cargo persist; permanent-death tombstones remain in the lineage.

## 10. Persistence and interoperability

**The durable Genesis record is authoritative; world NPCs are projections.** Use a stable `creatureId`, unique Sparks and `operationId`, revision/CAS, `activationEpoch`, operation journal and durable outbox. Offspring creation, Spark consumption, inventory transfers and revival require atomic checks. Reconciliation repairs world/store divergence after crashes. ECS saves and provider records **are not assumed to form one transaction**.

**Pluggable providers:** `auto`, `hytale_native`, `sqlite`, `postgresql`. Each must meet equivalent uniqueness, idempotency, concurrent-write and recovery guarantees. `auto` favors native Hytale storage when verified strong enough, otherwise SQLite. If a provider fails required integrity checks, refuse risky operations; do not silently start an empty replacement store. Cross-provider migration is explicit and backed up.

Never change unrelated NPCs. Genesis owns only voluntarily adopted creatures; safe release restores earlier behavior. Unknown critical extensions and registry versions fail closed. Namespaced public API events require explicit write permissions.

## 11. Onboarding and balancing targets

A skippable, resumable **Field Guide** teaches: (1) tracks → (2) observation → (3) taming → (4) commands → (5) gene analysis → (6) partner choice/Harmony → (7) Cradle/Spark → (8) care, growth and roles. Disabled/unavailable features are skipped. The guide grants no free pets or hidden genetic data.

**Playtest targets, not observed facts:** first companion ~11–16 min active play; first hybrid ~39–48 min; adult hybrid ~99–109 min calendar time. Arcane mutations are **optional**: varied research, 6/12/22% rates and one-time protection against unlucky streaks replace mandatory grind. XP rewards varied accomplishments and eight introductory quests rather than command spam, without a global daily XP cap. Each of the seven roles should have situational value.

## 12. Configuration, registries and release gates

**Single authoritative server config:** `Genesis_GDD_v0.7_Server_Config.json`. It defines code visibility, minigames, breeding chance/cooldowns, Sparks, growth, XP, magic research, death/summon rules, storage providers and the guide. The full JSON is not duplicated here. Operators may change difficulty but cannot silently change existing genomes or Genesis Code meaning.

**Normative registries:**

1. `Genesis_GDD_v0.7_Gene_Schema_v2.json` — gene definitions, legal variants and `HERITAGE`.
2. `Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json` — 28 forms, all breeding pairs, immutable IDs.
3. `Genesis_v0.7_Stage_10_Expression_Rules_v2.json` — exact expression formulas, pair rules, limits.
4. `Genesis_GDD_v0.7_Morphology_Set_v2.json` — anatomical slots and age rigs.
5. `Genesis_v0.7_Stage_08B_Content_Catalog_v1.json` — forms, stats, roles, spells, mutations, tutorials.

**Release gates:** no cloned vanilla NPCs; complete graph and gene coverage; genome-derived stats match anatomy; verified `GEN1` reproduction; hidden genes remain private; crash-safe no-duplication behavior; reliable storage providers; summon/death policies; accessible training and minigames; genuine player balance validation. Hytale Role IDs/assets, animation, combat, UI and native-storage transaction semantics remain **integration gates**, not established capabilities.
