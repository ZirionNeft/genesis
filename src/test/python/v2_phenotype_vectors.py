"""Independent exact-rational v0.7 pair-rule vectors; prints JSON and never edits specs/."""

from fractions import Fraction
import hashlib
import json
from pathlib import Path


DIRECTORY = Path(__file__).resolve().parents[3] / "specs/Genesis_GDD_v0.7"


def read(name):
    return json.loads((DIRECTORY / name).read_text(encoding="utf-8"))


def half_up(value, places):
    """Inputs have already been clamped to nonnegative minima; no decimal context or float rounding."""
    scaled = value * 10 ** places
    units = (2 * scaled.numerator + scaled.denominator) // (2 * scaled.denominator)
    return f"{units // 100}.{units % 100:02d}" if places == 2 else str(units)


def generate():
    expression = read("Genesis_v0.7_Stage_10_Expression_Rules_v2.json")
    content = read("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json")
    graph = read("Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json")
    schema = read("Genesis_GDD_v0.7_Gene_Schema_v2.json")
    defaults = {row["id"]: row["defaultVariant"] for row in content["genes"]}
    defaults.update({name: sorted(row["variants"])[0] for name, row in content["familyGeneDefinitions"].items()})
    forms = {row["formId"]: row for row in content["forms"]}
    policy = expression["statPolicy"]
    cases = []
    for gene, definition in expression["genes"].items():
        family_id = definition.get("families", ["genesis:canids"])[0]
        family = next(row for row in graph["families"] if row["familyId"] == family_id)
        founder = family["baseSpecies"][0]
        base = forms[founder]["baseStats"]
        for pair, expected in definition["pairRules"].items():
            effects = []
            for name in list(schema["coreGenes"]) + schema["familyGenes"][family_id]:
                key = pair if name == gene else "|".join([defaults[name]] * 2)
                effects.append(expression["genes"][name]["pairRules"][key])
            stats = {}
            for stat in base:
                delta = sum((Fraction(str(rule["multipliers"].get(stat, 0))) for rule in effects), Fraction())
                multiplier = min(Fraction(policy["statMultiplierMax"]), max(Fraction(policy["statMultiplierMin"]), 1 + delta))
                flat = sum((Fraction(str(rule["flat"].get(stat, 0))) for rule in effects), Fraction())
                value = max(Fraction(policy["minimum"][stat]), Fraction(str(base[stat])) * multiplier + flat)
                stats[stat] = half_up(value, 2 if stat == "speedBps" else 0)
            cases.append(dict(gene=gene, pair=pair, family=family_id, founder=founder,
                              state=expected["state"], visual=expected["visual"], utility=expected["utility"], stats=stats))
    digest = hashlib.sha256(json.dumps(expression, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    return dict(expressionSha256=digest, cases=cases)


if __name__ == "__main__":
    result = generate()
    print('{"expressionSha256":' + json.dumps(result["expressionSha256"]) + ',"cases":[')
    print(",\n".join(json.dumps(case, ensure_ascii=False, separators=(",", ":")) for case in result["cases"]))
    print("]}")
