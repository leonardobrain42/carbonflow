import copy
import json
import pathlib
import unittest

import vt0011_tool07_conformance as m

DOC = json.loads((pathlib.Path(__file__).parent.parent / "docs/data/vt0011-tool07-conformance.json").read_text(encoding="utf-8"))


class ConformanceTest(unittest.TestCase):
    def test_published_matrix_is_valid(self):
        self.assertEqual(m.validate(DOC), [])

    def test_central_questions_stay_open(self):
        states = {r["id"]: r["platformState"] for r in DOC["requirements"]}
        self.assertEqual(states["R11"], "CANNOT_DETERMINE")  # top-of-dispatch vs average
        self.assertEqual(states["R15"], "CANNOT_DETERMINE")  # time correspondence
        self.assertEqual(states["R18"], "CANNOT_DETERMINE")  # MDL-only usage right

    def test_rejects_claim_of_conformity(self):
        bad = copy.deepcopy(DOC)
        bad["requirements"][0]["evidence"] = "Série conforme e aprovado."
        self.assertTrue(any("conformidade" in p for p in m.validate(bad)))

    def test_gap_needs_what_to_resolve(self):
        bad = copy.deepcopy(DOC)
        del bad["requirements"][2]["toResolve"]
        self.assertTrue(any("toResolve" in p for p in m.validate(bad)))

    def test_unknown_source_rejected(self):
        bad = copy.deepcopy(DOC)
        bad["requirements"][0]["source"]["document"] = "OUTRA"
        self.assertTrue(m.validate(bad))


if __name__ == "__main__":
    unittest.main()
