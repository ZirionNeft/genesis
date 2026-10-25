#!/usr/bin/env python3
"""Read-only consistency checks for the supplied Genesis v0.7 design package.

Development tool, not a runtime loader, asset verifier or GEN1/RFC8785 codec.
No allowlist: a known design conflict still makes verification fail.
"""

import argparse
from dataclasses import asdict, dataclass
from decimal import Decimal, InvalidOperation
import hashlib
import itertools
import json
import math
from pathlib import Path
import re
import sys


FILES = {
    "schema": "Genesis_GDD_v0.7_Gene_Schema_v2.json",
    "graph": "Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json",
    "expression": "Genesis_v0.7_Stage_10_Expression_Rules_v2.json",
    "morphology": "Genesis_GDD_v0.7_Morphology_Set_v2.json",
    "content": "Genesis_v0.7_Stage_08B_Content_Catalog_v1.json",
    "config": "Genesis_GDD_v0.7_Server_Config.json",
}
DEFAULT_DIRECTORY = Path(__file__).resolve().parents[1] / "specs/Genesis_GDD_v0.7"
ID = re.compile(r"[a-z0-9_.-]+:[a-z0-9_./-]+\Z")
GENE = re.compile(r"[A-Z][A-Z0-9_]*\Z")
VARIANT = re.compile(r"[a-z][a-z0-9_]*\Z")
STATS = {"hp", "attack", "armor", "speedBps", "stamina", "cargoSlots", "senseRadius"}
MAX_FILE_BYTES = 2 * 1024 * 1024


@dataclass(frozen=True, order=True)
class Issue:
    path: str
    code: str
    message: str


@dataclass(frozen=True)
class Report:
    issues: tuple
    counts: dict

    @property
    def valid(self):
        return not self.issues

    def to_dict(self):
        return {"valid": self.valid, "counts": self.counts,
                "fingerprintConvention": "observed-v0.7-compact-sorted-json; not RFC8785",
                "issues": [asdict(issue) for issue in self.issues]}


class InvalidDocument(ValueError):
    def __init__(self, path, message, code="STRUCTURE"):
        super().__init__(message)
        self.issue = Issue(path, code, message)


class Node:
    """Small typed reader; invalid structures produce a location, not a traceback."""

    def __init__(self, value, path):
        self.value = value
        self.path = path

    def __getitem__(self, key):
        values = self.object()
        if key not in values:
            raise InvalidDocument(self.path + "/" + key, "Required field is missing")
        escaped = key.replace("~", "~0").replace("/", "~1")
        return Node(values[key], self.path + "/" + escaped)

    def object(self):
        if not isinstance(self.value, dict):
            raise InvalidDocument(self.path, "Expected an object")
        return self.value

    def entries(self):
        return [(key, self[key]) for key in self.object()]

    def array(self):
        if not isinstance(self.value, list):
            raise InvalidDocument(self.path, "Expected an array")
        return [Node(value, f"{self.path}/{index}") for index, value in enumerate(self.value)]

    def string(self, pattern=None):
        if not isinstance(self.value, str) or not self.value:
            raise InvalidDocument(self.path, "Expected a nonempty string")
        if pattern is not None and not pattern.fullmatch(self.value):
            raise InvalidDocument(self.path, "Invalid identifier")
        return self.value

    def strings(self, pattern=None):
        values = [item.string(pattern) for item in self.array()]
        if len(set(values)) != len(values):
            raise InvalidDocument(self.path, "Duplicate array entries", "DUPLICATE")
        return values

    def integer(self):
        if type(self.value) is not int:
            raise InvalidDocument(self.path, "Expected an integer (not a boolean)")
        return self.value

    def number(self, allow_string=False):
        if type(self.value) not in (int, float) and not (allow_string and isinstance(self.value, str)):
            raise InvalidDocument(self.path, "Expected a finite number")
        try:
            value = Decimal(str(self.value))
        except InvalidOperation:
            raise InvalidDocument(self.path, "Expected a finite decimal") from None
        if not value.is_finite():
            raise InvalidDocument(self.path, "Nonfinite numbers are forbidden")
        return value

    def boolean(self):
        if type(self.value) is not bool:
            raise InvalidDocument(self.path, "Expected a boolean")
        return self.value


def snapshot_fingerprint(value):
    """Reproduce fingerprints observed in this supplied package, NOT general JCS.

    Python's decimal/Unicode representation is not an RFC8785 implementation.
    Keep this dev audit convention separate from the future registry/GEN1 contract.
    """
    encoded = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"),
                         allow_nan=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


class Validator:
    def __init__(self, documents):
        self.docs = {key: Node(value, FILES[key] + "#") for key, value in documents.items() if key in FILES}
        self.issues = []
        self.counts = {}

    def require(self, condition, node, code, message):
        if not condition:
            self.issues.append(Issue(node.path, code, message))

    def same(self, node, expected, code="REFERENCE"):
        self.require(node.value == expected and type(node.value) is type(expected), node, code,
                     f"Expected {expected!r}, got {node.value!r}")

    def same_set(self, node, expected, pattern=None, code="REFERENCE"):
        actual = set(node.strings(pattern))
        wanted = set(expected)
        self.require(actual == wanted, node, code,
                     f"Expected {sorted(wanted)!r}, got {sorted(actual)!r}")

    def indexed(self, node, key, pattern=None):
        result = {}
        for entry in node.array():
            identifier = entry[key].string(pattern)
            self.require(identifier not in result, entry[key], "DUPLICATE", f"Duplicate ID: {identifier}")
            result[identifier] = entry
        return result

    def run(self):
        try:
            for key in FILES:
                if key not in self.docs:
                    raise InvalidDocument(FILES[key] + "#", "Required document is missing", "MISSING_DOCUMENT")
                self.docs[key].object()
            self.validate()
        except InvalidDocument as error:
            # Semantic errors accumulate; a malformed shape stops dependent checks safely.
            self.issues.append(error.issue)
        return Report(tuple(sorted(set(self.issues))), dict(self.counts))

    def validate(self):
        s, g, e, m, c, cfg = (self.docs[key] for key in FILES)
        for node, id_key, version_key, expected_id, version in [
            (s, "id", "version", "genesis:genes", 2),
            (g, "schemaId", "graphVersion", "genesis:hybrid_graph", 1),
            (e, "rulesetId", "rulesetVersion", "genesis:expression", 2),
            (m, "id", "version", "genesis:morphology", 2),
        ]:
            node[id_key].string(ID)
            node[version_key].integer()
            self.same(node[id_key], expected_id, "BASELINE")
            self.same(node[version_key], version, "BASELINE")
        self.same(g["crossFamilyBreeding"], False, "BASELINE")
        self.same(g["resolver"], "union-roots-v1", "BASELINE")
        self.same(s["heritageMutationAllowed"], False, "BASELINE")
        heritage = s["heritageGenes"].strings(GENE)
        self.same_set(s["heritageGenes"], ["HERITAGE_1", "HERITAGE_2", "HERITAGE_3"], GENE, "BASELINE")
        core = s["coreGenes"].object()
        self.require(len(core) == 23, s["coreGenes"], "BASELINE", "v0.7 requires 23 common genes")
        graph_families = self.indexed(g["families"], "familyId", ID)
        family_genes = {family: node.strings(GENE) for family, node in s["familyGenes"].entries()}
        for family in family_genes:
            Node(family, s["familyGenes"].path).string(ID)
        for node in [s["familyFounders"], s["geneCountByFamily"], e["families"], m["families"]]:
            self.require(set(node.object()) == set(family_genes), node, "FAMILIES", "Family sets differ")
        self.require(set(graph_families) == set(family_genes) and len(family_genes) == 4,
                     g["families"], "FAMILIES", "v0.7 requires the same four families in all registries")
        family_definitions = c["familyGeneDefinitions"].object()
        extra_genes = set(itertools.chain.from_iterable(family_genes.values()))
        self.require(set(family_definitions) == extra_genes, c["familyGeneDefinitions"], "GENES", "Family gene sets differ")
        self.require(not (set(core) & extra_genes or (set(core) | extra_genes) & set(heritage)),
                     s["coreGenes"], "GENES", "Common, family and heritage genes must be disjoint")
        gene_variants = {}
        for gene, node in s["coreGenes"].entries():
            Node(gene, node.path).string(GENE)
            gene_variants[gene] = node["variants"].strings(VARIANT)
        for gene in sorted(extra_genes):
            definition = c["familyGeneDefinitions"][gene]
            gene_variants[gene] = definition["variants"].strings(VARIANT)
            expected = {family.removeprefix("genesis:") for family, genes in family_genes.items() if gene in genes}
            self.same_set(definition["families"], expected)
        self.validate_genes(e, m, gene_variants, family_genes)
        all_forms = {}
        pair_count = 0
        for family, genes in family_genes.items():
            founders = s["familyFounders"][family].strings(ID)
            self.require(len(founders) == 3, s["familyFounders"][family], "FOUNDERS", "Expected three founders")
            expected_count = len(core) + len(genes) + len(heritage)
            s["geneCountByFamily"][family].integer()
            self.same(s["geneCountByFamily"][family], expected_count, "GENE_COUNT")
            graph_family = graph_families.get(family)
            if graph_family is None:
                continue
            rig = graph_family["rigId"].string(ID)
            self.same_set(graph_family["baseSpecies"], founders, ID)
            for node, founder_key in [(e["families"][family], "founders"), (m["families"][family], "founderSpecies")]:
                self.same(node["rigId"], rig)
                self.same_set(node[founder_key], founders, ID)
                self.same_set(node["familyGenes"], genes, GENE)
            self.same_set(e["families"][family]["heritageGenes"], heritage, GENE)
            self.same(e["families"][family]["historicalGraphVersion"], g["graphVersion"].value)
            forms, pairs = self.validate_graph_family(graph_family, founders, rig)
            self.require(not (set(all_forms) & set(forms)), graph_family, "DUPLICATE", "Form IDs must be globally unique")
            all_forms.update({key: (family, roots) for key, roots in forms.items()})
            pair_count += pairs
        self.counts.update(families=len(family_genes), forms=len(all_forms), pairs=pair_count,
                           commonGenes=len(core), expressionGenes=len(gene_variants))
        self.validate_content(c, e, all_forms, gene_variants, family_genes)
        self.validate_references(s, g, e, m, c, cfg)
        self.validate_config(cfg, c, e)

    def validate_genes(self, expression, morphology, variants, family_genes):
        rules = expression["genes"]
        self.require(set(rules.object()) == set(variants), rules, "GENES", "Expression gene set differs from schema/catalog")
        slots = set(morphology["slots"].strings())
        self.same_set(morphology["ageVariants"], ["infant", "juvenile", "adult"], code="BASELINE")
        self.same_set(expression["morphology"]["ageVariants"], morphology["ageVariants"].value)
        for node in [morphology, expression["morphology"]]:
            self.same(node["veteranUsesAdultRig"], True, "BASELINE")
        pair_count = 0
        for gene, choices in variants.items():
            self.require(bool(choices), rules, "VARIANTS", f"No variants for {gene}")
            definition = rules[gene]
            self.same_set(definition["variants"], choices, code="VARIANTS")
            self.same(definition["version"], 2, "BASELINE")
            if gene in set(itertools.chain.from_iterable(family_genes.values())):
                allowed = {family for family, genes in family_genes.items() if gene in genes}
                self.same_set(definition["families"], allowed, ID, "FAMILY_GENE_MEMBERSHIP")
            elif "families" in definition.object():
                self.same_set(definition["families"], family_genes, ID, "FAMILY_GENE_MEMBERSHIP")
            pairs = definition["pairRules"]
            expected = {"|".join(sorted(pair)) for pair in itertools.combinations_with_replacement(choices, 2)}
            self.require(set(pairs.object()) == expected, pairs, "PAIR_COVERAGE", f"Incomplete or invalid unordered pair table for {gene}")
            pair_count += len(pairs.object())
            visual_slot = definition["visualSlot"].value
            self.require(visual_slot is None or isinstance(visual_slot, str) and visual_slot in slots,
                         definition["visualSlot"], "VISUAL_SLOT", "Unknown visual slot")
            for _, rule in pairs.entries():
                rule["state"].string()
                for field in ["multipliers", "flat", "utility"]:
                    for name, value in rule[field].entries():
                        value.number(allow_string=True)
                        if field != "utility":
                            self.require(name in STATS, value, "STAT", "Unknown genetic stat")
                for slot, token in rule["visual"].entries():
                    token.string()
                    self.require(slot in slots, token, "VISUAL_SLOT", "Visual token uses an unregistered slot")
                    self.require(slot == visual_slot, token, "VISUAL_SLOT", "Visual slot differs from gene declaration")
        self.counts["expressionPairs"] = pair_count

    def validate_graph_family(self, node, founders, rig):
        forms = self.indexed(node["forms"], "id", ID)
        roots = {}
        expected_roots = {frozenset(group) for count in range(1, len(founders) + 1)
                          for group in itertools.combinations(founders, count)}
        for identifier, form in forms.items():
            support = frozenset(form["roots"].strings(ID))
            self.require(bool(support) and support <= set(founders), form["roots"], "ROOTS", "Roots must be nonempty founder subsets")
            self.same(form["rigId"], rig)
            self.same(form["kind"], "base" if len(support) == 1 else "hybrid")
            if len(support) == 1:
                self.require(support == {identifier}, form["id"], "ROOTS", "Founder ID must equal its sole root")
            roots[identifier] = support
        self.require(len(forms) == 7 and set(roots.values()) == expected_roots,
                     node["forms"], "FORM_COVERAGE", "Expected all seven distinct founder subsets")
        actual_pairs = set()
        pairs = node["allowedPairs"].array()
        for pair in pairs:
            a, b, child = (pair[key].string(ID) for key in ["parentA", "parentB", "childForm"])
            key = tuple(sorted((a, b)))
            self.require(key not in actual_pairs, pair, "DUPLICATE", "Repeated unordered parent pair")
            actual_pairs.add(key)
            if a not in roots or b not in roots or child not in roots:
                self.require(False, pair, "PAIR_REFERENCE", "All parents/children must be forms of this family")
            else:
                self.require(roots[child] == roots[a] | roots[b], pair["childForm"], "CHILD_ROOTS", "Child must have union of historical parent roots")
        expected_pairs = {tuple(sorted(pair)) for pair in itertools.combinations_with_replacement(forms, 2)}
        self.require(actual_pairs == expected_pairs, node["allowedPairs"], "PAIR_COVERAGE", "Expected all 28 unordered parent pairs")
        return roots, len(pairs)

    def validate_content(self, content, expression, forms, variants, family_genes):
        entries = self.indexed(content["forms"], "formId", ID)
        self.require(set(entries) == set(forms), content["forms"], "FORM_COVERAGE", "Catalog and graph forms differ")
        self.same(content["baseSpeciesCount"], 12, "BASELINE")
        roles = content["roles"].object()
        self.require(len(roles) == 7, content["roles"], "BASELINE", "Expected seven roles")
        for identifier, entry in entries.items():
            family = entry["familyId"].string(ID)
            raw_roots = entry["originRoots"].strings()
            roots = frozenset("genesis:" + root for root in raw_roots)
            if identifier in forms:
                self.require(forms[identifier] == (family, roots), entry, "ROOTS", "Catalog family/roots differ from graph")
            stats = entry["baseStats"]
            self.require(set(stats.object()) == STATS, stats, "STAT", "Expected exactly seven base stats")
            for _, value in stats.entries():
                self.require(value.number() >= 0, value, "RANGE", "Base stats cannot be negative")
            self.require(set(entry["allowedRoles"].strings()) <= set(roles), entry["allowedRoles"], "ROLE", "Unknown allowed role")
            self.require(entry["signaturePassive"]["requiredRole"].string() in roles,
                         entry["signaturePassive"]["requiredRole"], "ROLE", "Unknown passive role")
        genes = self.indexed(content["genes"], "id", GENE)
        expected_core = set(variants) - set(itertools.chain.from_iterable(family_genes.values()))
        self.require(set(genes) == expected_core, content["genes"], "GENES", "Catalog common genes differ from schema")
        for gene, definition in genes.items():
            for field in ["defaultVariant", "specialVariant"]:
                self.require(definition[field].string() in variants.get(gene, []), definition[field], "VARIANTS", "Unknown catalog variant")
        additional = content["additionalGenes"].object()
        expected_additional = {family.removeprefix("genesis:"): genes for family, genes in family_genes.items() if genes}
        self.require(set(additional) == set(expected_additional), content["additionalGenes"], "GENES", "Additional family genes differ")
        for family, genes in expected_additional.items():
            self.same_set(content["additionalGenes"][family], genes, GENE)
        mutations = self.indexed(content["mutations"], "id")
        self.require(len(mutations) == 21, content["mutations"], "BASELINE", "Expected 21 mutations")
        for _, mutation in mutations.items():
            gene = mutation["geneId"].string(GENE)
            self.require(gene in variants, mutation["geneId"], "MUTATION_TARGET", "Mutation must target a known non-HERITAGE gene")
            self.require(mutation["resultingVariant"].string() in variants.get(gene, []), mutation["resultingVariant"], "MUTATION_TARGET", "Unknown mutation variant")
            self.require(mutation["category"].string() in {"cosmetic", "physiological", "behavioral", "magical"}, mutation["category"], "MUTATION_CATEGORY", "Unknown mutation category")
            self.same(mutation["drawOnEligibleChildOnly"], True)
        missions = self.indexed(content["trainingMissions"], "id", ID)
        self.require(len(missions) == 8, content["trainingMissions"], "BASELINE", "Expected eight missions")
        for mission in missions.values():
            self.require(mission["xp"].integer() > 0, mission["xp"], "RANGE", "Mission XP must be positive")
            self.same(mission["firstCompletionOnly"], True)
        elements = variants.get("ELEMENT", [])
        self.same_set(content["elementVariants"], elements)
        self.same_set(content["elementExpressionPriority"], elements)
        spells = content["magicSpells"].object()
        self.require(set(spells) == set(elements), content["magicSpells"], "SPELL", "Each element requires one spell")
        spell_map = expression["abilityPolicy"]["spellMap"].object()
        self.require(set(spell_map) == set(elements), expression["abilityPolicy"]["spellMap"], "SPELL", "Element/spell map differs")
        for _, target in expression["abilityPolicy"]["spellMap"].entries():
            self.require(target.string() in spells, target, "SPELL", "Unknown spell target")
        self.counts.update(mutations=len(mutations), missions=len(missions), roles=len(roles), spells=len(spells))

    def validate_references(self, schema, graph, expression, morphology, content, config):
        for prefix, node, id_key, version_key in [
            ("geneSchema", schema, "id", "version"),
            ("hybridGraph", graph, "schemaId", "graphVersion"),
            ("morphologySet", morphology, "id", "version"),
        ]:
            self.same(expression[prefix + "Id"], node[id_key].value)
            self.same(expression[prefix + "Version"], node[version_key].value)
        references = config["genetics"]["registries"]
        expected = {"geneSchema", "hybridGraph", "expressionRules", "morphologySet"}
        self.require(set(references.object()) == expected, references, "REFERENCE", "Registry manifest differs")
        for key, node, id_key, version_key in [
            ("geneSchema", schema, "id", "version"), ("hybridGraph", graph, "schemaId", "graphVersion"),
            ("expressionRules", expression, "rulesetId", "rulesetVersion"), ("morphologySet", morphology, "id", "version"),
        ]:
            reference = references[key]
            self.same(reference["id"], node[id_key].value)
            self.same(reference["version"], node[version_key].value)
            self.same(reference["sha256"], snapshot_fingerprint(node.value), "FINGERPRINT")
        compatibility = expression["catalogCompatibility"]
        self.same(compatibility["contentCatalogVersion"], content["catalogVersion"].value)
        self.same(compatibility["graphVersion"], graph["graphVersion"].value)
        self.same(compatibility["contentCatalogSha256"], snapshot_fingerprint(content.value), "FINGERPRINT")
        self.same(compatibility["hybridGraphSha256"], snapshot_fingerprint(graph.value), "FINGERPRINT")

    def validate_config(self, config, content, expression):
        chances = config["genetics"]["mutation"]["probabilityByTier"]
        self.same(content["mutationRules"]["categoryProbabilityByTier"], chances.value, "CONFIG_MIRROR")
        self.require(set(chances.object()) == {"base", "discovery", "mastery"}, chances, "CONFIG", "Expected three research tiers")
        for _, tier in chances.entries():
            self.require(set(tier.object()) == {"cosmetic", "physiological", "behavioral", "magical"}, tier, "CONFIG", "Expected four mutation categories")
            total = Decimal(0)
            for _, chance in tier.entries():
                number = chance.number()
                self.require(0 <= number <= 1, chance, "RANGE", "Probability must be in [0,1]")
                total += number
            self.require(total <= 1, tier, "RANGE", "Exclusive mutation probabilities must sum to at most 1")
        for field in ["initialCradleCapacity", "upgradedCradleCapacity", "dispersalRefundRate", "unknownTraitsDefault", "noveltyResearchCredit"]:
            self.same(content["lifeSparks"][field], config["lifeSparks"][field].value, "CONFIG_MIRROR")
        for node in [content["mutationRules"]["maxMutationEventsPerChild"], config["breeding"]["sparksPerSuccess"]]:
            self.same(node, 1, "BASELINE")
        self.same(content["mutationRules"]["inheritanceAffectedByMinigame"], False, "BASELINE")
        self.same(content["mutationRules"]["guarantee"]["maxEligibleSuccessesWithoutFirstMagicalMutation"],
                  config["genetics"]["mutation"]["arcaneFocus"]["firstDiscoveryGuaranteeEligibleSuccessfulBirths"].value, "CONFIG_MIRROR")
        for name, catalog_key in [("ordinaryCandidateRate", "ordinaryCandidateArcanaAwakeProbability"),
                                  ("affinityCandidateRate", "affinityTaggedCandidateArcanaAwakeProbability")]:
            self.same(content["wildAffinityHypotheses"][catalog_key], config["genetics"]["wildArcana"][name].value, "CONFIG_MIRROR")
        self.same(content["rolesMaxActiveLearned"], config["progression"]["roleSkillSlots"].value, "CONFIG_MIRROR")
        self.same(content["maxInnateActive"], config["balance"]["maxInnateActiveSpells"].value, "CONFIG_MIRROR")
        self.same(expression["abilityPolicy"]["innateMaxActive"], content["maxInnateActive"].value)
        for bound, field in [("min", "geneticStatMultiplierMin"), ("max", "geneticStatMultiplierMax")]:
            self.same(content["geneticStatMultipliersCap"][bound], config["balance"][field].value, "CONFIG_MIRROR")
            self.require(expression["statPolicy"]["statMultiplier" + bound.capitalize()].number(True) == config["balance"][field].number(),
                         expression["statPolicy"], "CONFIG_MIRROR", "Stat multiplier bounds differ")
        # This is deliberately not validation of every server option: a typed runtime
        # config with feature-flag, lifecycle and storage semantics is a separate step.


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"Duplicate JSON object key: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"Nonfinite JSON number: {value}")


def finite_float(value):
    number = float(value)
    if not math.isfinite(number):
        raise ValueError(f"JSON number is outside the finite binary64 range: {value}")
    return number


def load_documents(directory):
    documents, issues = {}, []
    for key, filename in FILES.items():
        path = directory / filename
        try:
            with path.open("rb") as source:
                raw = source.read(MAX_FILE_BYTES + 1)
            if len(raw) > MAX_FILE_BYTES:
                raise ValueError("Design document exceeds 2 MiB limit")
            documents[key] = json.loads(raw.decode("utf-8"), object_pairs_hook=reject_duplicate_keys,
                                        parse_constant=reject_constant, parse_float=finite_float)
            # Reject unpaired surrogate escapes even in descriptive/unknown fields.
            json.dumps(documents[key], ensure_ascii=False, allow_nan=False).encode("utf-8")
        except (OSError, ValueError, UnicodeError, RecursionError) as error:
            issues.append(Issue(filename + "#", "INPUT", str(error)))
    return documents, issues


def validate_directory(directory):
    documents, issues = load_documents(Path(directory))
    if issues:
        return Report(tuple(sorted(issues)), {})
    return Validator(documents).run()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, default=DEFAULT_DIRECTORY)
    parser.add_argument("--json", action="store_true", help="Emit a deterministic machine-readable report")
    args = parser.parse_args(argv)
    report = validate_directory(args.directory)
    if args.json:
        print(json.dumps(report.to_dict(), ensure_ascii=False, indent=2))
    else:
        print("Genesis GDD v0.7: " + ("PASS" if report.valid else "FAIL"))
        print(", ".join(f"{key}={value}" for key, value in sorted(report.counts.items())))
        for issue in report.issues:
            print(f"{issue.code}: {issue.path}: {issue.message}")
        print("Design consistency only; no Hytale asset/API certification. Fingerprints: observed snapshot convention, not RFC8785.")
    return 0 if report.valid else 1


if __name__ == "__main__":
    sys.exit(main())
