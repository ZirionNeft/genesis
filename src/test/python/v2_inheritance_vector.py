"""Independent v2 gene-order/LCG vector. Stdout is a Java properties fixture."""

import json
from pathlib import Path


def main():
    directory = Path(__file__).resolve().parents[3] / "specs/Genesis_GDD_v0.7"
    schema = json.loads((directory / "Genesis_GDD_v0.7_Gene_Schema_v2.json").read_text(encoding="utf-8"))
    config = json.loads((directory / "Genesis_GDD_v0.7_Server_Config.json").read_text(encoding="utf-8"))
    family = "genesis:canids"
    genes = {"genesis:" + name.lower(): sorted("genesis:" + variant for variant in gene["variants"])
             for name, gene in schema["coreGenes"].items()}
    assert not schema["familyGenes"][family], "This vector covers the initial canid schema only"
    for name in schema["heritageGenes"]:
        genes["genesis:" + name.lower()] = sorted(schema["familyFounders"][family])
    seed = 42
    state = (seed ^ 0x5DEECE66D) & ((1 << 48) - 1)
    choices = []
    result = []
    for name, variants in sorted(genes.items(), key=lambda entry: tuple(entry[0].split(":", 1))):
        selected = []
        for _ in range(2):
            state = (state * 0x5DEECE66D + 0xB) & ((1 << 48) - 1)
            bit = state >> 47
            choices.append(str(bit))
            selected.append(variants[-1] if bit else variants[0])
        result.append((name, *sorted(selected)))
    print("# Independent Python v2_inheritance_vector.py; both parents carry first/last variant at each gene")
    print("algorithm=genesis:mendelian_gene_id_order_v2")
    print("seed=" + str(seed))
    print("family=" + family)
    print("schemaSha256=" + config["genetics"]["registries"]["geneSchema"]["sha256"])
    print("geneCount=" + str(len(genes)))
    print("choices=" + "".join(choices))
    for index, (name, first, second) in enumerate(result):
        print(f"gene.{index}.id={name}")
        print(f"gene.{index}.first={first}")
        print(f"gene.{index}.second={second}")


if __name__ == "__main__":
    main()
