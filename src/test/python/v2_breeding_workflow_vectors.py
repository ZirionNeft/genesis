"""Independent seed scheme + Java LCG48 + complete v2 pre-mutation inheritance vectors."""

from fractions import Fraction
import hashlib
import json
from pathlib import Path
import struct


ROOT = Path(__file__).resolve().parents[3] / "specs/Genesis_GDD_v0.7"


def read(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


def stream_seed(seed, label):
    encoded = b"GENS" + struct.pack(">iq", 1, seed)
    digest = hashlib.sha256(b"genesis:rng\0" + encoded + label.encode("ascii")).digest()
    return struct.unpack(">q", digest[:8])[0]


class Lcg48:
    def __init__(self, seed):
        self.state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)

    def bits(self, count):
        self.state = (self.state * 0x5DEECE66D + 11) & ((1 << 48) - 1)
        return self.state >> (48 - count)

    def unit(self):
        return ((self.bits(26) << 27) + self.bits(27)) / (1 << 53)


def generate():
    schema = read("Genesis_GDD_v0.7_Gene_Schema_v2.json")
    content = read("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json")
    graph = read("Genesis_v0.7_Stage_02_Initial_Hybrid_Graph_v1.json")
    config = read("Genesis_GDD_v0.7_Server_Config.json")
    cases = []
    seeds = [0, 42, -1, -(1 << 63), (1 << 63) - 1, 42 + (1 << 48)]
    for family in graph["families"]:
        genes = {"genesis:" + name.lower(): sorted("genesis:" + variant for variant in row["variants"])
                 for name, row in schema["coreGenes"].items()}
        for name in schema["familyGenes"][family["familyId"]]:
            genes["genesis:" + name.lower()] = sorted("genesis:" + variant for variant in content["familyGeneDefinitions"][name]["variants"])
        for name in schema["heritageGenes"]:
            genes["genesis:" + name.lower()] = sorted(family["baseSpecies"])
        triad = next(row["id"] for row in family["forms"] if len(row["roots"]) == 3)
        for cross in (False, True):
            a = {name: [variants[0], variants[-1]] for name, variants in sorted(genes.items())}
            # Asymmetric parental pairs expose accidental parent reordering and skipped homozygous draws.
            b = {name: [pair[index % 2]] * 2 for index, (name, pair) in enumerate(a.items())}
            if cross:
                for name in schema["heritageGenes"]:
                    a["genesis:" + name.lower()] = [family["baseSpecies"][0]] * 2
                    b["genesis:" + name.lower()] = [family["baseSpecies"][1]] * 2
            for seed in ([42] if cross else seeds):
                chance = config["breeding"]["baseSameFamilySuccess" if cross else "baseSameFormSuccess"]
                draw = Lcg48(stream_seed(seed, "attempt")).unit()
                success = Fraction.from_float(draw) < Fraction(str(chance))
                case = dict(family=family["familyId"], seed=str(seed), parentA=a, parentB=b,
                            formA=family["baseSpecies"][0] if cross else triad,
                            formB=family["baseSpecies"][1] if cross else triad,
                            chance=str(chance), drawBits=struct.pack(">d", draw).hex(), success=success)
                if success:
                    rng = Lcg48(stream_seed(seed, "inheritance"))
                    inherited = {}
                    for name in sorted(genes, key=lambda key: tuple(key.split(":", 1))):
                        inherited[name] = sorted([a[name][rng.bits(1)], b[name][rng.bits(1)]])
                    case.update(inherited=inherited, mutationSeed=str(stream_seed(seed, "mutation")),
                                cosmeticSeed=str(stream_seed(seed, "cosmetic")))
                cases.append(case)
    return dict(registries=config["genetics"]["registries"], cases=cases)


if __name__ == "__main__":
    result = generate()
    print('{"registries":' + json.dumps(result["registries"], separators=(",", ":")) + ',"cases":[')
    print(",\n".join(json.dumps(case, separators=(",", ":")) for case in result["cases"]))
    print("]}")
