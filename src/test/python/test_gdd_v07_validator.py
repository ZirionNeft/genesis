"""Regression tests for the design audit; fixture mutations never touch specs/."""

import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("genesis_gdd_validator", ROOT / "tools/validate_gdd_v07.py")
audit = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = audit
SPEC.loader.exec_module(audit)


def refresh_fingerprints(documents):
    """Keep a mutated test fixture internally hashed, exposing semantic errors."""
    references = documents["config"]["genetics"]["registries"]
    for key in ("schema", "graph", "morphology"):
        manifest_key = {"schema": "geneSchema", "graph": "hybridGraph", "morphology": "morphologySet"}[key]
        references[manifest_key]["sha256"] = audit.snapshot_fingerprint(documents[key])
    compatibility = documents["expression"]["catalogCompatibility"]
    compatibility["contentCatalogSha256"] = audit.snapshot_fingerprint(documents["content"])
    compatibility["hybridGraphSha256"] = audit.snapshot_fingerprint(documents["graph"])
    references["expressionRules"]["sha256"] = audit.snapshot_fingerprint(documents["expression"])


class CatalogValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original, issues = audit.load_documents(audit.DEFAULT_DIRECTORY)
        if issues:
            raise AssertionError(issues)
        cls.fixture = copy.deepcopy(cls.original)

    def setUp(self):
        self.documents = copy.deepcopy(self.fixture)

    def report(self, rehash=True):
        if rehash:
            refresh_fingerprints(self.documents)
        return audit.Validator(self.documents).run()

    def assert_issue(self, code, path_fragment=None, rehash=True):
        report = self.report(rehash)
        self.assertFalse(report.valid)
        matches = [issue for issue in report.issues if issue.code == code]
        self.assertTrue(matches, report.issues)
        if path_fragment:
            self.assertTrue(any(path_fragment in issue.path for issue in matches), matches)
        return report

    def test_real_package_passes_without_rewriting_sources(self):
        before = {key: hashlib.sha256((audit.DEFAULT_DIRECTORY / name).read_bytes()).hexdigest()
                  for key, name in audit.FILES.items()}
        report = audit.validate_directory(audit.DEFAULT_DIRECTORY)
        self.assertTrue(report.valid, report.issues)
        self.assertFalse(report.issues)
        self.assertEqual(report.counts["forms"], 28)
        self.assertEqual(report.counts["pairs"], 112)
        self.assertEqual(report.counts["expressionPairs"], 111)
        self.assertEqual(before, {key: hashlib.sha256((audit.DEFAULT_DIRECTORY / name).read_bytes()).hexdigest()
                                  for key, name in audit.FILES.items()})

    def test_coherent_in_memory_fixture_passes(self):
        self.assertTrue(self.report().valid)

    def test_missing_horn_family_is_rejected_even_with_matching_hash(self):
        self.documents["expression"]["genes"]["HORN_FORM"]["families"] = ["genesis:heavies"]
        report = self.assert_issue("FAMILY_GENE_MEMBERSHIP", "/genes/HORN_FORM/families")
        self.assertEqual([(issue.code, issue.path) for issue in report.issues], [
            ("FAMILY_GENE_MEMBERSHIP", audit.FILES["expression"] + "#/genes/HORN_FORM/families")])

    def test_fingerprint_mismatch_is_not_ignored(self):
        self.documents["schema"]["serialization"] += " changed"
        self.assert_issue("FINGERPRINT", "/registries/geneSchema/sha256", rehash=False)

    def test_registry_version_is_independent_of_transport_version(self):
        self.documents["schema"]["version"] = 1
        self.assert_issue("BASELINE", "/version")

    def test_missing_graph_pair_is_rejected_even_with_matching_hash(self):
        self.documents["graph"]["families"][0]["allowedPairs"].pop()
        self.assert_issue("PAIR_COVERAGE", "/allowedPairs")

    def test_reverse_parent_pair_is_still_a_duplicate(self):
        pairs = self.documents["graph"]["families"][0]["allowedPairs"]
        pair = copy.deepcopy(pairs[1])
        pair["parentA"], pair["parentB"] = pair["parentB"], pair["parentA"]
        pairs.append(pair)
        self.assert_issue("DUPLICATE", "/allowedPairs")

    def test_cross_family_parent_is_rejected(self):
        self.documents["graph"]["families"][0]["allowedPairs"][0]["parentB"] = "genesis:horse"
        self.assert_issue("PAIR_REFERENCE")

    def test_wrong_child_historical_roots_are_rejected(self):
        self.documents["graph"]["families"][0]["allowedPairs"][0]["childForm"] = "genesis:wolf_fox"
        self.assert_issue("CHILD_ROOTS", "/childForm")

    def test_duplicate_form_id_does_not_crash_validation(self):
        self.documents["graph"]["families"][0]["forms"][1]["id"] = "genesis:wolf"
        self.assert_issue("DUPLICATE")

    def test_family_membership_is_checked_beyond_per_gene_declaration(self):
        self.documents["expression"]["families"]["genesis:runners"]["familyGenes"] = []
        self.assert_issue("REFERENCE", "/families/genesis:runners/familyGenes")

    def test_declared_family_gene_count_is_checked(self):
        self.documents["schema"]["geneCountByFamily"]["genesis:canids"] = 12
        self.assert_issue("GENE_COUNT")

    def test_missing_expression_pair_is_rejected(self):
        del self.documents["expression"]["genes"]["BODY_FRAME"]["pairRules"]["large|normal"]
        self.assert_issue("PAIR_COVERAGE")

    def test_noncanonical_pair_key_is_rejected(self):
        pairs = self.documents["expression"]["genes"]["BODY_FRAME"]["pairRules"]
        pairs["normal|large"] = pairs.pop("large|normal")
        self.assert_issue("PAIR_COVERAGE")

    def test_unknown_visual_slot_is_rejected(self):
        self.documents["expression"]["genes"]["BODY_FRAME"]["pairRules"]["normal|normal"]["visual"] = {"antlers": "huge"}
        self.assert_issue("VISUAL_SLOT")

    def test_unknown_stat_is_rejected(self):
        self.documents["expression"]["genes"]["BODY_FRAME"]["pairRules"]["normal|normal"]["flat"] = {"magicLuck": "2"}
        self.assert_issue("STAT")

    def test_mutation_cannot_target_heritage(self):
        self.documents["content"]["mutations"][0]["geneId"] = "HERITAGE_1"
        self.assert_issue("MUTATION_TARGET")

    def test_mutation_cannot_invent_an_allele(self):
        self.documents["content"]["mutations"][0]["resultingVariant"] = "invisible"
        self.assert_issue("MUTATION_TARGET")

    def test_catalog_form_roots_match_graph(self):
        self.documents["content"]["forms"][0]["originRoots"] = ["fox"]
        self.assert_issue("ROOTS")

    def test_config_and_catalog_probability_mirrors_match(self):
        self.documents["content"]["mutationRules"]["categoryProbabilityByTier"]["base"]["magical"] = 0.9
        self.assert_issue("CONFIG_MIRROR")

    def test_mutation_probabilities_are_exclusive(self):
        for table in [self.documents["config"]["genetics"]["mutation"]["probabilityByTier"],
                      self.documents["content"]["mutationRules"]["categoryProbabilityByTier"]]:
            table["mastery"] = dict.fromkeys(table["mastery"], 0.5)
        self.assert_issue("RANGE")

    def test_wrong_array_type_has_a_structured_location(self):
        self.documents["expression"]["genes"]["HORN_FORM"]["families"] = "genesis:heavies"
        self.assert_issue("STRUCTURE", "/genes/HORN_FORM/families")

    def test_duplicate_variants_are_rejected(self):
        self.documents["schema"]["coreGenes"]["BODY_FRAME"]["variants"].append("normal")
        self.assert_issue("DUPLICATE")

    def test_missing_required_field_has_a_location(self):
        del self.documents["schema"]["heritageGenes"]
        self.assert_issue("STRUCTURE", "#/heritageGenes")

    def test_boolean_is_not_a_gene_count(self):
        self.documents["schema"]["geneCountByFamily"]["genesis:canids"] = True
        self.assert_issue("STRUCTURE", "/geneCountByFamily/genesis:canids")

    def test_missing_element_definition_does_not_crash(self):
        del self.documents["schema"]["coreGenes"]["ELEMENT"]
        self.assert_issue("GENES")

    def test_report_is_deterministic(self):
        first = audit.validate_directory(audit.DEFAULT_DIRECTORY).to_dict()
        second = audit.validate_directory(audit.DEFAULT_DIRECTORY).to_dict()
        self.assertEqual(first, second)


class InputAndCliTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        for filename in audit.FILES.values():
            (self.directory / filename).write_bytes((audit.DEFAULT_DIRECTORY / filename).read_bytes())

    def write_schema(self, raw):
        (self.directory / audit.FILES["schema"]).write_bytes(raw.encode("utf-8"))
        report = audit.validate_directory(self.directory)
        self.assertFalse(report.valid)
        self.assertEqual(report.issues[0].code, "INPUT", report.issues)
        return report

    def test_duplicate_json_keys_are_rejected(self):
        report = self.write_schema('{"id":"genesis:genes","id":"forged:genes"}')
        self.assertIn("Duplicate JSON", report.issues[0].message)

    def test_nonfinite_json_is_rejected(self):
        for value in ["NaN", "Infinity", "-Infinity", "1e9999"]:
            with self.subTest(value=value):
                self.write_schema('{"number":' + value + '}')

    def test_unpaired_surrogate_is_rejected(self):
        self.write_schema(r'{"description":"\ud800"}')

    def test_missing_file_is_a_structured_failure(self):
        (self.directory / audit.FILES["content"]).unlink()
        report = audit.validate_directory(self.directory)
        self.assertEqual(report.issues[0].code, "INPUT")

    def test_invalid_utf8_is_rejected(self):
        (self.directory / audit.FILES["schema"]).write_bytes(b'\xff')
        self.assertEqual(audit.validate_directory(self.directory).issues[0].code, "INPUT")

    def test_cli_failure_is_machine_readable_without_a_traceback(self):
        documents, issues = audit.load_documents(self.directory)
        self.assertFalse(issues)
        documents["expression"]["genes"]["HORN_FORM"]["families"] = ["genesis:heavies"]
        refresh_fingerprints(documents)
        for key, filename in audit.FILES.items():
            (self.directory / filename).write_text(json.dumps(documents[key], ensure_ascii=False), encoding="utf-8")
        result = subprocess.run([sys.executable, "-B", str(ROOT / "tools/validate_gdd_v07.py"),
                                 "--directory", str(self.directory), "--json"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stderr, "")
        self.assertFalse(json.loads(result.stdout)["valid"])

    def test_cli_success_uses_unmodified_catalog_copy(self):
        result = subprocess.run([sys.executable, "-B", str(ROOT / "tools/validate_gdd_v07.py"),
                                 "--directory", str(self.directory), "--json"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(json.loads(result.stdout)["valid"])


if __name__ == "__main__":
    unittest.main()
