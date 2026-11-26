import unittest

from build_fleet_model_registrations import plan

ORG = "71e71a49-e926-4eaa-aab8-86b04add8a41"


def cand(i, name, key, line, version="GS 5 EV", state="ACCEPTED", model="DOLPHIN MINI", period="2026-09"):
    return {"id": i, "literalName": name, "recordKey": key, "observationPeriod": period, "state": state,
            "selectedPbevLine": line, "matches": [{"brand": "BYD", "model": model, "version": version,
                                                   "category": "Sub Compacto", "pbevLine": line}]}


def decisions(*items):
    return {"organizationId": ORG, "decisions": [{"candidateId": i, "vehicleClass": c, "propulsion": p} for i, c, p in items]}


class RegistrationPlanTest(unittest.TestCase):
    def test_nothing_is_defaulted_without_a_decision(self):
        out = plan([cand("a", "I/BYD DOLPHIN MINI", "I_BYD_DOLPHIN_MINI", 62)], decisions())
        self.assertEqual([], out["registrations"])
        self.assertIn("sem decisão", out["refused"][0]["reason"])

    def test_registration_uses_the_chosen_line_and_the_declared_values(self):
        out = plan([cand("a", "I/BYD DOLPHIN MINI", "i_byd_dolphin_mini", 62)], decisions(("a", "LDV", "BEV")))
        body = out["registrations"][0]["body"]
        self.assertEqual(("BYD", "DOLPHIN MINI", "GS 5 EV", "LDV", "BEV", 2026, "I_BYD_DOLPHIN_MINI"),
                         (body["manufacturer"], body["modelName"], body["variant"], body["vehicleClass"],
                          body["propulsion"], body["aliasYear"], body["sourceRecordKey"]))
        self.assertEqual(ORG, body["organizationId"])
        self.assertRegex(out["registrations"][0]["idempotencyKey"], r"^[A-Za-z0-9._-]{1,128}$")

    def test_values_outside_the_vocabulary_and_pending_candidates_are_refused(self):
        out = plan([cand("a", "A", "A", 1), cand("b", "B", "B", 2, state="PENDING")],
                   decisions(("a", "LIGHT", "BEV"), ("b", "LDV", "BEV")))
        self.assertEqual([], out["registrations"])
        self.assertEqual(2, len(out["refused"]))

    def test_a_line_without_a_printed_version_is_refused(self):
        out = plan([cand("a", "A", "A", 1, version=None)], decisions(("a", "LDV", "BEV")))
        self.assertEqual([], out["registrations"])
        self.assertIn("versão", out["refused"][0]["reason"])

    def test_same_model_twice_becomes_an_extra_alias_and_conflicting_declarations_are_refused(self):
        a, b, c = (cand("a", "N1", "K1", 62), cand("b", "N2", "K2", 62), cand("c", "N3", "K3", 62))
        out = plan([a, b, c], decisions(("a", "LDV", "BEV"), ("b", "LDV", "BEV"), ("c", "HDV", "BEV")))
        self.assertEqual(1, len(out["registrations"]))
        self.assertEqual(["b"], [x["candidateId"] for x in out["additionalAliases"]])
        self.assertEqual(["c"], [x["candidateId"] for x in out["refused"]])

    def test_alias_keys_that_collide_after_uppercasing_are_refused(self):
        out = plan([cand("a", "N1", "k1", 62), cand("b", "N2", "K1", 63, version="X")],
                   decisions(("a", "LDV", "BEV"), ("b", "LDV", "BEV")))
        self.assertEqual(1, len(out["registrations"]))
        self.assertEqual(1, len(out["refused"]))

    def test_the_decision_basis_is_passed_through_never_converted(self):
        owner = dict(cand("a", "N1", "K1", 62), decisionBasis="OWNER_INTERNAL")
        out = plan([owner], decisions(("a", "LDV", "BEV")))
        self.assertEqual("OWNER_INTERNAL", out["registrations"][0]["decisionBasis"])
        legacy = plan([cand("b", "N2", "K2", 63, version="Z")], decisions(("b", "LDV", "BEV")))
        self.assertIsNone(legacy["registrations"][0]["decisionBasis"])

    def test_invalid_organization_is_an_error(self):
        with self.assertRaises(ValueError):
            plan([], {"organizationId": "x", "decisions": []})


if __name__ == "__main__":
    unittest.main()
