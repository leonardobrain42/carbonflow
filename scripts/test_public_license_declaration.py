import json
import os
import re
import unittest

ROOT = os.path.join(os.path.dirname(__file__), "..")


def load(path):
    return json.load(open(os.path.join(ROOT, path), encoding="utf-8"))


class DeclarationTest(unittest.TestCase):
    def setUp(self):
        self.decl = load("docs/data/mcti-license-declaration.json")
        self.evidence = load("docs/data/evidence/mcti-license-capture.json")
        self.migration = open(os.path.join(ROOT, "backend/src/main/resources/db/migration/V112__public_license_declaration.sql"), encoding="utf-8").read()

    def test_payload_matches_the_preserved_evidence(self):
        d, e = self.decl, self.evidence
        self.assertEqual(d["evidenceSha256"], e["page"]["sha256OfSavedHtml"])
        self.assertEqual(d["legalTextSha256"], e["legalText"]["sha256OfSavedHtml"])
        self.assertEqual(d["edition"], e["workbook"]["edition"])
        self.assertTrue(d["edition"].endswith(e["workbook"]["sha256"][:8]))
        self.assertEqual(d["evidenceCapturedAt"], e["page"]["capturedAt"])

    def test_source_seeded_by_the_migration_matches_the_workbook(self):
        self.assertIn(self.decl["sourceId"], self.migration)
        self.assertIn(self.decl["edition"], self.migration)
        self.assertIn(self.evidence["workbook"]["sha256"], self.migration)
        self.assertIn(self.evidence["workbook"]["uri"], self.migration)

    def test_nothing_invented_and_nothing_beyond_the_licence(self):
        d = self.decl
        self.assertIsNone(d["endsAt"])  # no fixed term was found
        self.assertEqual(d["startsAtBasis"], "CAPTURE_DATE")  # no proven earlier availability
        self.assertEqual(d["startsAt"], d["evidenceCapturedAt"])
        ops = {o["operation"]: o["groundKind"] for o in d["operations"]}
        self.assertNotIn("EXPORT_DERIVATIVES", ops)  # ND: redistributing a modified work is not covered
        self.assertEqual(ops["EXTRACT_TEXT"], "LEGAL_INTERPRETATION")  # argued, not granted by a clause
        for o in d["operations"]:
            self.assertGreaterEqual(len(o["grounds"]), 20)
        self.assertFalse(re.search(r"(?i)ofício|protocolo n", json.dumps({k: v for k, v in d.items() if k != "note"}, ensure_ascii=False)))


if __name__ == "__main__":
    unittest.main()
