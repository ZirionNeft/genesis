"""Independent wild RNG/policy-hash/genotype vectors. Weights are TEST FIXTURES, not game balance."""

from fractions import Fraction
import hashlib
import itertools
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[3] / "specs/Genesis_GDD_v0.7"


def text(value):
    encoded = value.encode("utf-8")
    return struct.pack(">i", len(encoded)) + encoded


def draw(seed, founder, stream):
    digest = hashlib.sha256(b"genesis:wild_rng_v1\0" + struct.pack(">q", seed) + text(founder) + text(stream)).digest()
    state = (int.from_bytes(digest[:8], "big") ^ 0x5DEECE66D) & ((1 << 48) - 1)
    state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
    first = state >> 22
    state = (state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
    return ((first << 27) + (state >> 21)) / (1 << 53)


def distribution(variants):
    pairs = list(itertools.combinations_with_replacement(sorted(variants), 2))
    return [(pair, Fraction(0) if i == 0 else Fraction(5, 100) if i < len(pairs) - 1
             else 1 - Fraction(5, 100) * (len(pairs) - 2)) for i, pair in enumerate(pairs)]


def decimal(value):
    # Fixture weights have at most two decimal places.
    return format(float(value), ".2f").rstrip("0").rstrip(".") or "0"


def table_bytes(table):
    result = struct.pack(">i", len(table))
    for (a, b), weight in table:
        result += text(a) + text(b) + text(decimal(weight))
    return result


def pick(table, value):
    cumulative = Fraction(0)
    for pair, weight in table:
        cumulative += weight
        if Fraction.from_float(value) < cumulative:
            return list(pair)
    raise AssertionError("incomplete table")


def generate():
    def read(name):
        return json.loads((ROOT / name).read_text(encoding="utf-8"))
    schema = read("Genesis_GDD_v0.7_Gene_Schema_v2.json")
    content = read("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json")
    config = read("Genesis_GDD_v0.7_Server_Config.json")
    profiles = {}
    for family, founders in schema["familyFounders"].items():
        genes = {"genesis:" + name.lower(): distribution(["genesis:" + v for v in row["variants"]])
                 for name, row in schema["coreGenes"].items() if name != "ARCANA"}
        for name in schema["familyGenes"][family]:
            genes["genesis:" + name.lower()] = distribution(["genesis:" + v for v in content["familyGeneDefinitions"][name]["variants"]])
        for founder in founders:
            profiles[founder] = (family, genes)
    awakened = [(('genesis:awake', 'genesis:awake'), Fraction(1, 4)),
                (('genesis:awake', 'genesis:quiet'), Fraction(3, 4))]
    schema_pin = config["genetics"]["registries"]["geneSchema"]
    policy_id = "test:wild_fixture"
    encoded = text("genesis:wild_distribution_binary_v1") + text(policy_id) + struct.pack(">i", 1)
    encoded += text(schema_pin["id"]) + struct.pack(">i", schema_pin["version"]) + text(schema_pin["sha256"])
    encoded += struct.pack(">i", len(profiles))
    for founder, (_, genes) in sorted(profiles.items()):
        encoded += text(founder) + struct.pack(">i", len(genes))
        for gene, table in sorted(genes.items()):
            encoded += text(gene) + table_bytes(table)
        encoded += table_bytes(awakened)
    cases = []
    for founder, (family, genes) in sorted(profiles.items()):
        first = schema["familyFounders"][family][0]
        seeds = [0, 42, -1, -(1 << 63), (1 << 63) - 1, 42 + (1 << 48)] if founder == first else [42]
        for affinity in ["ORDINARY", "AFFINITY_TAGGED"]:
            rate = Fraction(str(config["genetics"]["wildArcana"]["ordinaryCandidateRate" if affinity == "ORDINARY" else "affinityCandidateRate"]))
            additional = []
            if founder == first:
                # Pin both conditional awake pairs as well as quiet candidates at default rates.
                for homo in [True, False]:
                    additional.append(next(s for s in range(100000)
                                           if Fraction.from_float(draw(s, founder, "arcana_gate")) < rate
                                           and (draw(s, founder, "arcana_awake_pair") < .25) == homo))
            for seed in dict.fromkeys(seeds + additional):
                genome = {gene: pick(table, draw(seed, founder, gene)) for gene, table in sorted(genes.items())}
                for name in schema["heritageGenes"]:
                    genome["genesis:" + name.lower()] = [founder, founder]
                gate = draw(seed, founder, "arcana_gate")
                genome["genesis:arcana"] = pick(awakened, draw(seed, founder, "arcana_awake_pair")) if Fraction.from_float(gate) < rate else ["genesis:quiet"] * 2
                cases.append(dict(founder=founder, family=family, seed=str(seed), affinity=affinity,
                                  gateBits=struct.pack(">d", gate).hex(), genome=dict(sorted(genome.items()))))
    return dict(schema=schema_pin, policyId=policy_id, policySha256=hashlib.sha256(encoded).hexdigest(), cases=cases)


if __name__ == "__main__":
    print(json.dumps(generate(), ensure_ascii=False, indent=2))
