import copy
import json
import os
import unittest

import registry_permissions as rp

DOC = json.load(open(os.path.join(os.path.dirname(__file__), "..", "docs", "data", "registry-permissions.json"), encoding="utf-8"))


class MatrixTest(unittest.TestCase):
    def test_versioned_matrix_is_valid(self):
        self.assertEqual(rp.validate(DOC), [])

    def test_automated_extraction_prohibited_where_text_says_so(self):
        self.assertEqual(rp.status(DOC, "ACR", "EXTRACT_AUTOMATED"), "PROHIBITED")
        self.assertEqual(rp.status(DOC, "GOLD_STANDARD", "EXTRACT_AUTOMATED"), "PROHIBITED")

    def test_unread_or_unknown_is_never_allowed_by_omission(self):
        for registry in ("VERRA", "UNFCCC", "CAR", "CAD_TRUST", "INEXISTENTE"):
            for op in rp.OPERATIONS:
                self.assertEqual(rp.status(DOC, registry, op), "UNDETERMINED")

    def test_block_is_per_operation_not_per_registry(self):
        self.assertEqual(rp.status(DOC, "ACR", "CONSULT"), "ALLOWED")
        self.assertEqual(rp.status(DOC, "ACR", "REDISTRIBUTE"), "PROHIBITED")

    def test_rejects_permissions_on_unread_registry(self):
        bad = copy.deepcopy(DOC)
        next(r for r in bad["registries"] if r["id"] == "VERRA")["permissions"] = {"CONSULT": {"status": "ALLOWED", "basis": "x"}}
        self.assertTrue(any("não lido" in p for p in rp.validate(bad)))

    def test_rejects_conditional_without_conditions_and_missing_operation(self):
        bad = copy.deepcopy(DOC)
        acr = next(r for r in bad["registries"] if r["id"] == "ACR")
        acr["permissions"]["PROCESS"]["conditions"] = []
        del acr["permissions"]["EXPORT"]
        problems = rp.validate(bad)
        self.assertTrue(any("sem condições" in p for p in problems))
        self.assertTrue(any("exatamente" in p for p in problems))


if __name__ == "__main__":
    unittest.main()
