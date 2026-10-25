"""Read-only inventory of explicit vanilla species candidates. Never enables a species or interprets the NPC DSL."""

import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import zipfile


# Technical search candidates, not a gameplay allowlist. Variants belonging to factions/tamed roles are excluded.
CANDIDATES = {
    "wolf": ["Wolf_Black", "Wolf_White"], "fox": ["Fox"], "hyena": ["Hyena"],
    "horse": ["Horse"], "deer": ["Deer_Doe", "Deer_Stag"], "antelope": ["Antelope"],
    "bison": ["Bison"], "boar": ["Boar"], "mosshorn": ["Mosshorn", "Mosshorn_Plain"],
    "raven": ["Raven"], "owl": ["Owl_Brown", "Owl_Snow"], "hawk": ["Hawk"],
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def audit(assets, server, expected_server_version="0.7.4"):
    with zipfile.ZipFile(server) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        version = next(line.split(": ", 1)[1].strip() for line in manifest.splitlines() if line.startswith("Implementation-Version: "))
    if version != expected_server_version:
        raise ValueError(f"Expected server {expected_server_version}, got {version}")
    with zipfile.ZipFile(assets) as archive:
        names = set(archive.namelist())
        def by_id(prefix, asset_id):
            matches = sorted(name for name in names if name.startswith(prefix) and name.rsplit("/", 1)[-1] == asset_id + ".json")
            if len(matches) != 1:
                raise ValueError(f"Expected one {prefix}{asset_id} file, got {len(matches)}")
            return matches[0]
        def read(path):
            data = archive.read(path)
            return json.loads(data), digest(data)
        species = []
        for founder, candidates in CANDIDATES.items():
            roles = []
            for role_id in candidates:
                path = by_id("Server/NPC/Roles/", role_id)
                role, sha = read(path)
                appearance = role.get("Modify", {}).get("Appearance", role.get("Appearance"))
                if isinstance(appearance, dict) and set(appearance) == {"Compute"}:
                    # Resolve a single direct parameter binding only. No general expression evaluation or inheritance.
                    appearance = role.get("Parameters", {}).get(appearance["Compute"], {}).get("Value")
                row = dict(roleId=role_id, path=path, sha256=sha, type=role.get("Type"), reference=role.get("Reference"),
                           appearance=appearance if isinstance(appearance, str) else None,
                           directTameRoleChange=role.get("Modify", {}).get("TameRoleChange"))
                if isinstance(appearance, str):
                    model_path = by_id("Server/Models/", appearance)
                    model, model_sha = read(model_path)
                    resources = {}
                    for field in ["Model", "Texture"]:
                        resource = "Common/" + model[field] if isinstance(model.get(field), str) else None
                        resources[field] = dict(path=resource, exists=resource in names if resource else None,
                                                sha256=digest(archive.read(resource)) if resource in names else None)
                    clips = {}
                    for name, animation_set in sorted(model.get("AnimationSets", {}).items()):
                        declared = ["Common/" + clip["Animation"] for clip in animation_set.get("Animations", []) if isinstance(clip.get("Animation"), str)]
                        clips[name] = dict(declaredFiles=len(declared), allDeclaredFilesExist=bool(declared) and all(item in names for item in declared))
                    row["model"] = dict(id=appearance, path=model_path, sha256=model_sha, parent=model.get("Parent"), inheritanceResolved=False,
                                        resources=resources, declaredAnimationSets=clips)
                row["genesisEligibility"] = "UNVERIFIED"
                roles.append(row)
            species.append(dict(founderId="genesis:" + founder, candidateRoles=roles,
                                rigCompatibility="UNVERIFIED", initialGenesisAge="UNDECIDED",
                                controllerAndOwnership="UNVERIFIED", adoption="UNVERIFIED"))
    return dict(auditedOn=date.today().isoformat(), serverVersion=version,
                serverJarSha256=digest(server.read_bytes()), archiveBytes=assets.stat().st_size,
                scope="Static file inventory; not role loading, animation playback, rig compatibility, ownership or adoption acceptance",
                species=species)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--server-jar", type=Path, required=True)
    parser.add_argument("--expected-server-version", default="0.7.4",
                        help="Exact expected JAR version (default: 0.7.4); does not verify the Assets.zip version")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(audit(args.assets, args.server_jar, args.expected_server_version), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
