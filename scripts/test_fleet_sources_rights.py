import json
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..")
GRANTING = {"GRANTED_BY_LICENSE": "LICENSE_GRANT", "LEGAL_INTERPRETATION_DECLARED": "LEGAL_INTERPRETATION"}


def load(path):
    return json.load(open(os.path.join(ROOT, path), encoding="utf-8"))


def text(path):
    return open(os.path.join(ROOT, path), encoding="utf-8").read()


class FleetSourcesRightsTest(unittest.TestCase):
    def setUp(self):
        self.matrix = load("docs/data/fleet-sources-rights-matrix.json")
        self.sources = {s["sourceId"]: s for s in self.matrix["sources"]}
        self.manifest = load("docs/evidence/CF-OPEN-005-2026-09/source-manifest.json")
        self.migration = text("backend/src/main/resources/db/migration/V119__fleet_sources_rights_and_candidate_provenance.sql")
        self.java = text("backend/src/main/java/br/carbonflow/pilot/PublicLicenses.java")

    def test_each_declaration_is_exactly_what_the_matrix_grants(self):
        for source in self.sources.values():
            decl = load(source["declaration"])
            self.assertEqual(source["sourceId"], decl["sourceId"])
            self.assertEqual(source["edition"], decl["edition"])
            self.assertEqual(source["licenseIdentifier"], decl["licenseIdentifier"])
            expected = {o["platformOperation"]: GRANTING[o["status"]] for o in source["operations"] if o["status"] in GRANTING}
            self.assertEqual(expected, {o["operation"]: o["groundKind"] for o in decl["operations"]}, source["sourceId"])

    def test_blocked_or_refused_operations_are_never_declared(self):
        for source in self.sources.values():
            declared = {o["operation"] for o in load(source["declaration"])["operations"]}
            for op in source["operations"]:
                if op["status"] in ("BLOCKED_NOT_GROUNDED", "REFUSED_BY_LICENSE", "NOT_APPLICABLE") and op["platformOperation"]:
                    self.assertNotIn(op["platformOperation"], declared, op["operation"])
        pbev = {o["operation"]: o for o in self.sources["INMETRO-PBEV"]["operations"]}
        self.assertEqual("REFUSED_BY_LICENSE", pbev["export"]["status"])  # ND
        for blocked in ("unit_conversion", "derived_calculation", "display_extracted", "rating_value_extraction", "ai_operations"):
            self.assertEqual("BLOCKED_NOT_GROUNDED", pbev[blocked]["status"], blocked)
        self.assertEqual("LEGAL_INTERPRETATION_DECLARED", pbev["factual_extraction"]["status"])

    def test_no_declared_operation_is_outside_what_the_platform_models(self):
        terms = {}
        for match in re.finditer(r'new Terms\("([A-Za-z0-9.-]+)"(.*?)\n\s*"[^"]*"\)', self.java, re.S):
            sets = re.findall(r"Set\.(?:of|copyOf)\(([^)]*)\)", match.group(2))
            granted = set(re.findall(r'"([A-Z_]+)"', sets[0]))
            refused = set(re.findall(r'"([A-Z_]+)"', sets[1])) if len(sets) > 1 else set()
            terms[match.group(1)] = (granted, refused)
        for source in self.sources.values():
            granted, refused = terms[source["licenseIdentifier"]]
            for o in load(source["declaration"])["operations"]:
                self.assertNotIn(o["operation"], refused, o["operation"])
                if o["groundKind"] == "LICENSE_GRANT":
                    self.assertIn(o["operation"], granted, o["operation"])

    def test_evidence_hashes_and_sources_match_manifest_and_migration(self):
        files = {d["file"]: d for d in self.manifest["downloads"]}
        ren, pbev = load("docs/data/evidence/renavam-license-capture.json"), load("docs/data/evidence/pbev-license-capture.json")
        self.assertEqual(files["renavam-2026-09.zip"]["sha256"], ren["resource"]["file"]["sha256"])
        self.assertEqual(files["renavam-2026-09.zip"]["bytes"], ren["resource"]["file"]["sizeBytes"])
        self.assertEqual(files["pbev-2026.pdf"]["sha256"], pbev["pdf"]["sha256"])
        for source, evidence, sha in ((self.sources["SENATRAN-RENAVAM-FROTA"], ren, ren["resource"]["file"]),
                                      (self.sources["INMETRO-PBEV"], pbev, pbev["pdf"])):
            self.assertTrue(source["edition"].endswith(sha["sha256"][:8]))
            self.assertEqual(source["edition"], sha["edition"])
            self.assertIn(source["sourceId"], self.migration)
            self.assertIn(source["edition"], self.migration)
            self.assertIn(sha["sha256"], self.migration)
        d_ren, d_pbev = load("docs/data/renavam-license-declaration.json"), load("docs/data/pbev-license-declaration.json")
        self.assertEqual(ren["page"]["sha256OfSavedResponse"], d_ren["evidenceSha256"])
        self.assertEqual(pbev["page"]["sha256OfSavedHtml"], d_pbev["evidenceSha256"])
        self.assertEqual(pbev["legalText"]["sha256OfSavedHtml"], d_pbev["legalTextSha256"])
        self.assertEqual(load("docs/data/mcti-license-declaration.json")["legalTextSha256"], d_pbev["legalTextSha256"])
        self.assertEqual("other-pd", ren["page"]["license_id"])

    def test_nothing_invented_and_nothing_promoted(self):
        for name in ("renavam", "pbev"):
            d = load(f"docs/data/{name}-license-declaration.json")
            self.assertIsNone(d["endsAt"])
            self.assertEqual("CAPTURE_DATE", d["startsAtBasis"])
            self.assertEqual(d["startsAt"], d["evidenceCapturedAt"])
            self.assertEqual("AUTHORIZED_PROCESSING", d["purpose"])
            self.assertTrue(d["evidenceUri"].startswith("https://"))
            for o in d["operations"]:
                self.assertGreaterEqual(len(o["grounds"]), 20)
                self.assertLessEqual(len(o["grounds"]), 2000)
            self.assertIn("NÃO é aprovação", d["note"])
        self.assertIn("não admite dados", self.matrix["note"])


if __name__ == "__main__":
    unittest.main()
