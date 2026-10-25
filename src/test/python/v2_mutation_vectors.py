"""Exact Fraction + independent Java LCG48/nextInt vectors for all 21 v0.7 mutation IDs."""

from fractions import Fraction
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SPECS = ROOT / "specs/Genesis_GDD_v0.7"


class Random48:
    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def bits(self, count):
        self.state = (self.state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        return self.state >> (48 - count)

    def unit(self):
        return Fraction((self.bits(26) << 27) + self.bits(27), 1 << 53)

    def index(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self.bits(31)) >> 31
        while True:
            bits = self.bits(31)
            value = bits % bound
            if bits - value + bound - 1 < (1 << 31):
                return value


def generate():
    content = json.loads((SPECS / "Genesis_v0.7_Stage_08B_Content_Catalog_v1.json").read_text())
    config = json.loads((SPECS / "Genesis_GDD_v0.7_Server_Config.json").read_text())
    definitions = sorted(content["mutations"], key=lambda row: row["id"])
    categories = ["cosmetic", "physiological", "behavioral", "magical"]

    def mutate(genome, seed, tier="base"):
        rng = Random48(seed)
        draw = rng.unit()
        end = Fraction()
        category = None
        for name in categories:
            end += Fraction(str(config["genetics"]["mutation"]["probabilityByTier"][tier][name]))
            if draw < end:
                category = name
                break
        if category is None:
            return dict(reason="NO_EVENT", award=False)
        eligible = [row for row in definitions if row["category"] == category
                    and any(allele != "genesis:" + row["resultingVariant"] for allele in genome["genesis:" + row["geneId"].lower()])]
        if not eligible:
            return dict(reason="CATEGORY_UNAVAILABLE", award=False)
        mutation = eligible[rng.index(len(eligible))]
        gene = "genesis:" + mutation["geneId"].lower()
        target = "genesis:" + mutation["resultingVariant"]
        pair = genome[gene]
        copies = [i for i, value in enumerate(pair) if value != target]
        copy = copies[rng.index(len(copies))]
        after = list(pair)
        after[copy] = target
        return dict(id="genesis:" + mutation["id"], category=category, gene=gene, copy=copy,
                    previous=pair[copy], target=target, after=sorted(after), award=category == "magical")

    genome = {"genesis:" + row["id"].lower(): ["genesis:" + row["defaultVariant"]] * 2 for row in content["genes"]}
    for i in range(1, 4): genome[f"genesis:heritage_{i}"] = ["genesis:wolf"] * 2
    events = {}
    for seed in range(20000):
        result = mutate(genome, seed)
        if "id" in result:
            events.setdefault((result["id"], result["copy"]), dict(seed=str(seed), expected=result))
        if len(events) == 42:
            break
    assert len(events) == 42
    pre = json.loads((ROOT / "src/test/resources/breeding-v2-vectors.json").read_text())
    workflow = []
    for i, case in enumerate(pre["cases"]):
        workflow.append(dict(inputIndex=i, expected=mutate(case["inherited"], int(case["mutationSeed"])) if case["success"] else None))
    digest = hashlib.sha256(json.dumps(content, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
    return dict(contentSha256=digest, isolatedInput=genome, events=[events[key] for key in sorted(events)], workflow=workflow)


if __name__ == "__main__":
    print(json.dumps(generate(), ensure_ascii=False, indent=2))
