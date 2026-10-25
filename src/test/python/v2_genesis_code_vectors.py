"""Independent closed-domain RFC8785/JCS + Base64url + CRC32 GEN1 vectors (stdlib only)."""

import base64
import itertools
import json
from pathlib import Path
import zlib

ROOT = Path(__file__).resolve().parents[3] / "specs/Genesis_GDD_v0.7"


def canonical(value):
    if isinstance(value, str):
        value.encode("utf-8", errors="strict")
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    if type(value) is int and -(1 << 31) <= value < (1 << 31):
        return str(value)
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(canonical(key) + ":" + canonical(value[key])
                               for key in sorted(value, key=lambda key: key.encode("utf-16-be"))) + "}"
    raise ValueError("outside closed GEN1 domain")


def base64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def wire(payload):
    data = canonical(payload).encode("utf-8")
    return "GEN1." + base64url(data) + "." + format(zlib.crc32(data), "08X")


def generate():
    def read(name):
        return json.loads((ROOT / name).read_text(encoding="utf-8"))
    schema = read("Genesis_GDD_v0.7_Gene_Schema_v2.json")
    content = read("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json")
    config = read("Genesis_GDD_v0.7_Server_Config.json")
    expression = read("Genesis_v0.7_Stage_10_Expression_Rules_v2.json")
    cases = []
    for index, form in enumerate(content["forms"]):
        family = form["familyId"]
        variants = {"genesis:" + name.lower(): ["genesis:" + value for value in row["variants"]]
                    for name, row in schema["coreGenes"].items()}
        for name in schema["familyGenes"][family]:
            variants["genesis:" + name.lower()] = ["genesis:" + value for value in content["familyGeneDefinitions"][name]["variants"]]
        genes = {name: list(list(itertools.combinations_with_replacement(sorted(values), 2))[index % (len(values) * (len(values) + 1) // 2)])
                 for name, values in variants.items()}
        roots = ["genesis:" + root for root in form["originRoots"]]
        for i, name in enumerate(schema["heritageGenes"]):
            genes["genesis:" + name.lower()] = sorted([roots[2 * i % len(roots)], roots[(2 * i + 1) % len(roots)]])
        seed = [-(1 << 63), -1, 0, 42, (1 << 63) - 1, 42 + (1 << 48)][index % 6]
        payload = dict(payloadVersion=2, registries=config["genetics"]["registries"],
                       contentCatalog=dict(id=content["catalogVersion"], sha256=expression["catalogCompatibility"]["contentCatalogSha256"]),
                       familyId=family, historicalForm=form["formId"], genes=genes, visualSeed=str(seed), extensions=[])
        cases.append(dict(payload=payload, canonical=canonical(payload), code=wire(payload)))
    for kind, data in [("required", bytes(range(256))), ("optional_cosmetic", b"\0\xff\x80{not JSON}\0"), ("required", b"")]:
        payload = json.loads(json.dumps(cases[0]["payload"]))
        payload["extensions"] = [dict(definition=dict(id="example:unknown", version=(1 << 31) - 1, sha256="0" * 64),
                                      schemaVersion=(1 << 31) - 1, kind=kind, payload=base64url(data))]
        cases.append(dict(payload=payload, canonical=canonical(payload), code=wire(payload)))
    jcs_values = [{"\u20ac": "Euro Sign", "\r": "Carriage Return", "\ufb33": "Hebrew Letter Dalet With Dagesh",
                   "1": "One", "\U0001f600": "Emoji: Grinning Face", "\u0080": "Control", "\u00f6": "Latin Small Letter O With Diaeresis"},
                  {"\ue000": "BMP", "\U00010000": "Supplementary", "control": "".join(chr(i) for i in range(32))},
                  {"nested": [{"z": "</script> / \\ \" \u2028\u2029", "a": "е\u0308 / ё"}], "integer": (1 << 31) - 1}]
    return dict(cases=cases, jcs=[dict(value=value, canonical=canonical(value)) for value in jcs_values])


if __name__ == "__main__":
    print(json.dumps(generate(), ensure_ascii=False, indent=2))
