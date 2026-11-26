import unittest

from reconcile_fleet_catalog import brand_token, run

SHA = "b" * 64
PBEV = {"source_sha256": SHA, "rows": [
    {"line": 62, "category": "Sub Compacto", "brand": "BYD", "model": "DOLPHIN MINI", "version": "GS 5 EV", "propulsion": "ELETRICO"},
    {"line": 70, "category": "Médio", "brand": "GREAT WALL", "model": "ORA 03", "version": "BEV58 C", "propulsion": "ELETRICO"},
]}
STOCK = {"selected_literal_models": {
    "I/BYD DOLPHIN MINI GS5EV": {"registered_count": 600}, "I/GWM ORA 03 BEV58 C": {"registered_count": 300},
    "I/OUTRO SEM LINHA": {"registered_count": 100}, "I/BYD REJEITADO": {"registered_count": 0}}}


def cand(i, name, key, line, state="ACCEPTED", sha=SHA, basis="OWNER_INTERNAL"):
    return {"id": i, "literalName": name, "recordKey": key, "observationPeriod": "2026-09", "state": state,
            "selectedPbevLine": line if state == "ACCEPTED" else None, "pbevSourceSha256": sha, "decisionBasis": basis}


CANDS = [cand("a", "I/BYD DOLPHIN MINI GS5EV", "I_BYD_DOLPHIN_MINI_GS5EV", 62),
         cand("b", "I/GWM ORA 03 BEV58 C", "I_GWM_ORA_03_BEV58_C", 70),
         cand("c", "I/BYD REJEITADO", "I_BYD_REJEITADO", None, state="REJECTED")]


def decision(i, cls="LDV", prop="BEV", reason="Conferido pelo responsável do produto.", cev="ficha do fabricante, p. 2"):
    return {"candidateId": i, "vehicleClass": cls, "propulsion": prop, "reason": reason, "classEvidence": cev}


class ReconcileTest(unittest.TestCase):
    def test_brand_token_drops_only_the_import_marker(self):
        self.assertEqual("BYD", brand_token("I/BYD DOLPHIN"))
        self.assertEqual("GWM", brand_token("  i/GWM ORA 03"))

    def test_dossier_carries_the_pbev_evidence_and_never_decides_class(self):
        out = run(CANDS, PBEV, STOCK)
        d = {x["candidateId"]: x for x in out["dossiers"]}
        self.assertEqual(["a", "b"], sorted(d))
        self.assertEqual(62, d["a"]["pbev"]["line"])
        self.assertEqual("ELETRICO", d["a"]["pbev"]["propulsionPrinted"])
        self.assertEqual("BEV", d["a"]["suggestion"]["propulsion"])
        self.assertIsNone(d["a"]["suggestion"]["vehicleClass"])
        self.assertEqual(600, d["a"]["registeredVehicles"])
        self.assertEqual("OWNER_INTERNAL", d["a"]["decisionBasis"])
        self.assertEqual([], out["classificationPlan"])  # no decisions, no plan
        self.assertFalse(out["usableForE8"])

    def test_manufacturer_reconciliation_needs_people_for_a_different_brand_name(self):
        out = run(CANDS, PBEV, STOCK)
        states = {x["candidateId"]: x["manufacturer"]["state"] for x in out["dossiers"]}
        self.assertEqual({"a": "EXACT", "b": "UNRECONCILED"}, states)
        mapped = run(CANDS, PBEV, STOCK, {"GWM": "GREAT WALL"})
        self.assertEqual("MAPPED_BY_PEOPLE", {x["candidateId"]: x["manufacturer"]["state"] for x in mapped["dossiers"]}["b"])
        wrong = run(CANDS, PBEV, STOCK, {"GWM": "OUTRA MARCA"})
        self.assertEqual("UNRECONCILED", {x["candidateId"]: x["manufacturer"]["state"] for x in wrong["dossiers"]}["b"])

    def test_summary_is_over_the_sample_only(self):
        s = run(CANDS, PBEV, STOCK)["summary"]
        self.assertEqual(4, s["sampleNames"])
        self.assertEqual(1000, s["sampleRegisteredVehicles"])
        self.assertEqual(900, s["byStatus"]["ACCEPTED"]["registeredVehicles"])
        self.assertEqual(0.9, s["byStatus"]["ACCEPTED"]["shareOfSample"])
        self.assertEqual(1, s["byStatus"]["NO_CANDIDATE"]["names"])
        self.assertEqual(1, s["byStatus"]["REJECTED"]["names"])
        self.assertTrue(any("não é cobertura nacional" in x for x in run(CANDS, PBEV, STOCK)["limitations"]))

    def test_known_classification_is_planned_with_composed_evidence(self):
        out = run(CANDS, PBEV, STOCK, decisions={"decisions": [decision("a")]}, org="o1")
        item = out["classificationPlan"][0]
        self.assertEqual({"manufacturer": "BYD", "modelName": "DOLPHIN MINI", "variant": "GS 5 EV"}, item["model"])
        self.assertEqual(("LDV", "BEV"), (item["body"]["vehicleClass"], item["body"]["propulsion"]))
        ev = item["body"]["evidence"]
        for part in (SHA[:12], "linha 62", "ELETRICO", "I/BYD DOLPHIN MINI GS5EV", "EXACT", "ficha do fabricante"):
            self.assertIn(part, ev)
        self.assertLessEqual(len(ev), 1000)
        self.assertRegex(item["idempotencyKey"], r"^[A-Za-z0-9._-]{1,128}$")

    def test_refusals_never_default(self):
        decisions = {"decisions": [
            decision("zzz"),                                  # no dossier
            decision("a", prop="PHEV"),                       # contradicts the printed electric propulsion
            decision("a", cev=""),                            # class evidence missing
            decision("a", reason="curto"),                    # reason too short
            decision("b"),                                    # manufacturer unreconciled
            decision("a", cls="UNKNOWN", prop="BEV"),         # unknown only on one side
            decision("a", cls="CAR")]}                        # outside the vocabulary
        out = run(CANDS, PBEV, STOCK, decisions=decisions)
        self.assertEqual([], out["classificationPlan"])
        reasons = " | ".join(r["reason"] for r in out["refused"])
        for needle in ("sem dossiê", "contradiz", "evidência da classe", "10 e 1000", "não conciliado", "só valem juntas", "fora dos valores"):
            self.assertIn(needle, reasons)

    def test_unknown_stays_pending_without_class_evidence_even_when_unreconciled(self):
        out = run(CANDS, PBEV, STOCK, decisions={"decisions": [decision("b", "UNKNOWN", "UNKNOWN", cev="")]})
        self.assertEqual(1, len(out["classificationPlan"]))
        self.assertEqual({"vehicleClass": "UNKNOWN", "propulsion": "UNKNOWN",
                          "reason": "Conferido pelo responsável do produto.", "evidence": None}, out["classificationPlan"][0]["body"])

    def test_candidates_from_another_pbev_file_get_no_dossier(self):
        other = [cand("a", "I/BYD DOLPHIN MINI GS5EV", "K", 62, sha="c" * 64)]
        out = run(other, PBEV, STOCK, decisions={"decisions": [decision("a")]})
        self.assertEqual([], out["dossiers"])
        self.assertIn("outro arquivo do PBEV", out["refused"][0]["reason"])

    def test_two_aliases_of_one_model_share_one_classification_and_conflicts_are_refused(self):
        twin = cand("d", "I/BYD DOLPHIN MINI GS5EV2", "I_BYD_DOLPHIN_MINI_GS5EV2", 62)
        both = run(CANDS + [twin], PBEV, STOCK, decisions={"decisions": [decision("a"), decision("d")]})
        self.assertEqual(1, len(both["classificationPlan"]))
        self.assertEqual(["d"], [x["candidateId"] for x in both["classificationPlan"][0]["supportingAliases"]])
        clash = run(CANDS + [twin], PBEV, STOCK, decisions={"decisions": [decision("a"), decision("d", "HDV", "BEV")]})
        self.assertEqual(1, len(clash["classificationPlan"]))
        self.assertIn("classificações diferentes", clash["refused"][0]["reason"])

    def test_long_evidence_is_refused_not_truncated(self):
        out = run(CANDS, PBEV, STOCK, decisions={"decisions": [decision("a", cev="x" * 990)]})
        self.assertEqual([], out["classificationPlan"])
        self.assertIn("1000", out["refused"][0]["reason"])


if __name__ == "__main__":
    unittest.main()
