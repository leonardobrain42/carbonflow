import csv
import json
import os
import re
import tempfile
import unittest
from pathlib import Path

import prepare_alias_review as par

ROOT = Path(__file__).resolve().parent.parent
JAVA = (ROOT / "backend/src/main/java/br/carbonflow/pilot/FleetAliasCandidates.java").read_text(encoding="utf-8")
SEN, PBEV = "d" * 64, "a" * 64


def cand(name, level, count, lines, key=None):
    return {"literal_name": name, "record_key": key if key is not None else re.sub(r"[^A-Za-z0-9._-]", "_", name),
            "registered_count": count,
            "match": {"status": level, "level": level, "reason": "Casamento de nome.", "candidates": [
                {"brand": "BYD", "model": "DOLPHIN", "version": f"V{n}", "category": "Médio", "pbev_line": n} for n in lines]}}


CANDIDATES = {"observation_period": "2026-09", "approved_aliases": [], "refused_key_collisions": {"A_B": ["A B", "A_B"]},
              "candidates": [cand("I/EXACT 1", "EXACT", 500, [1]), cand("RENAULT/MEGANE X", "MODEL_ONLY", 9000, [10, 11]),
                             cand("I/AMB 1", "AMBIGUOUS", 5, [20, 21]), cand("A B", "EXACT", 3, [30]), cand("A_B", "EXACT", 2, [31])]}
MANIFEST = {"source": {"sha256": SEN}, "pbev": {"source_sha256": PBEV}}


class PrepareTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def sheet(self):
        with (self.dir / "decision-sheet.csv").open(encoding="utf-8", newline="") as stream:
            return list(csv.DictReader(stream))

    def test_prepare_leaves_every_decision_blank_and_orders_by_registered_count(self):
        summary = par.prepare(CANDIDATES, MANIFEST, self.dir)
        rows = self.sheet()
        self.assertEqual(["RENAULT/MEGANE X", "I/EXACT 1", "I/AMB 1"], [r["literal_name"] for r in rows])
        for row in rows:
            for column in ("decision", "selected_pbev_line", "acknowledged_risks", "reason"):
                self.assertEqual("", row[column])
        self.assertEqual(0, summary["decisions_prefilled"])
        self.assertFalse(summary["usableForE8"])
        self.assertEqual(["A B", "A_B"], summary["left_out_key_collisions"])
        self.assertIn("HOMONYM", rows[0]["risks_to_acknowledge"])
        self.assertIn("10: BYD DOLPHIN V10 | 11:", rows[0]["pbev_options"])

    def test_import_batches_carry_both_source_hashes_and_respect_the_limit(self):
        many = dict(CANDIDATES, candidates=[cand(f"I/BYD X{n}", "EXACT", n + 1, [1]) for n in range(1203)], refused_key_collisions={})
        par.prepare(many, MANIFEST, self.dir)
        files = sorted(self.dir.glob("alias-candidates-import-*.json"))
        self.assertEqual(3, len(files))
        bodies = [json.loads(f.read_text(encoding="utf-8")) for f in files]
        self.assertEqual([500, 500, 203], [len(b["items"]) for b in bodies])
        for b in bodies:
            self.assertEqual((SEN, PBEV, "2026-09"), (b["senatranSourceSha256"], b["pbevSourceSha256"], b["observationPeriod"]))
        self.assertEqual({"literalName", "recordKey", "level", "reason", "matches"}, set(bodies[0]["items"][0]))

    def test_manifest_without_hashes_is_refused(self):
        with self.assertRaises(par.ReviewError):
            par.prepare(CANDIDATES, {"source": {}, "pbev": {}}, self.dir)

    def queue(self):
        return [{"id": f"id-{c['literal_name']}", "literalName": c["literal_name"], "state": "PENDING", "level": c["match"]["level"],
                 "matches": [{"pbevLine": m["pbev_line"]} for m in c["match"]["candidates"]]} for c in CANDIDATES["candidates"]]

    def rows(self, **filled):
        base = {"literal_name": "", "decision": "", "selected_pbev_line": "", "acknowledged_risks": "", "reason": ""}
        return [dict(base, **f) for f in filled["rows"]]

    def run_decisions(self, rows):
        return par.decisions(rows, self.queue(), "INDEPENDENT", self.dir)

    def test_blank_rows_stay_pending_and_valid_decisions_become_bulk_items(self):
        result = self.run_decisions(self.rows(rows=[
            {"literal_name": "I/EXACT 1", "decision": "accepted", "selected_pbev_line": "1", "reason": "Nome e versão conferidos."},
            {"literal_name": "RENAULT/MEGANE X", "decision": "REJECTED", "reason": "Versão a combustão, não elétrica."},
            {"literal_name": "I/AMB 1"}]))
        self.assertEqual((2, 1), (result["decisions"], result["still_pending"]))
        body = json.loads((self.dir / "decisions-bulk-001.json").read_text(encoding="utf-8"))
        self.assertEqual({"outcome": "ACCEPTED", "candidateId": "id-I/EXACT 1", "reason": "Nome e versão conferidos.",
                          "basis": "INDEPENDENT", "selectedPbevLine": 1, "acknowledgedRisks": []}, body["items"][0])

    def test_non_exact_acceptance_needs_every_risk_written_by_the_reviewer(self):
        ok = {"literal_name": "RENAULT/MEGANE X", "decision": "ACCEPTED", "selected_pbev_line": "10", "reason": "Versão conferida manualmente."}
        with self.assertRaisesRegex(par.ReviewError, "reconheça cada risco"):
            self.run_decisions(self.rows(rows=[ok]))
        with self.assertRaisesRegex(par.ReviewError, "VERSION_UNCONFIRMED"):
            self.run_decisions(self.rows(rows=[dict(ok, acknowledged_risks="NAME_MATCH_ONLY HOMONYM DISTINCT_PROPULSION")]))
        self.run_decisions(self.rows(rows=[dict(ok, acknowledged_risks="NAME_MATCH_ONLY,HOMONYM,DISTINCT_PROPULSION,VERSION_UNCONFIRMED")]))
        body = json.loads((self.dir / "decisions-bulk-001.json").read_text(encoding="utf-8"))
        self.assertEqual(4, len(body["items"][0]["acknowledgedRisks"]))

    def test_invalid_rows_abort_everything_and_write_nothing(self):
        bad = [{"literal_name": "I/EXACT 1", "decision": "ACCEPTED", "selected_pbev_line": "99", "reason": "Linha fora das opções."},
               {"literal_name": "I/AMB 1", "decision": "REJECTED", "selected_pbev_line": "20", "reason": "Recusa com linha indevida."},
               {"literal_name": "I/EXACT 1", "decision": "MAYBE", "reason": "Decisão desconhecida aqui."},
               {"literal_name": "NÃO EXISTE", "decision": "REJECTED", "reason": "Sem candidato na fila."},
               {"literal_name": "RENAULT/MEGANE X", "decision": "REJECTED", "reason": "curto"}]
        with self.assertRaises(par.ReviewError) as caught:
            self.run_decisions(self.rows(rows=bad))
        self.assertEqual(5, len(str(caught.exception).splitlines()))
        self.assertEqual([], list(self.dir.glob("decisions-bulk-*.json")))

    def test_already_decided_candidates_are_not_decided_again_and_bulk_is_capped(self):
        queue = self.queue()
        queue[0]["state"] = "ACCEPTED"
        with self.assertRaisesRegex(par.ReviewError, "PENDING"):
            par.decisions(self.rows(rows=[{"literal_name": "I/EXACT 1", "decision": "REJECTED", "reason": "Já decidido antes."}]),
                          queue, "INDEPENDENT", self.dir)
        many_queue = [{"id": f"i{n}", "literalName": f"N{n}", "state": "PENDING", "level": "EXACT", "matches": [{"pbevLine": 1}]} for n in range(230)]
        rows = self.rows(rows=[{"literal_name": f"N{n}", "decision": "REJECTED", "reason": "Recusado em lote de teste."} for n in range(230)])
        result = par.decisions(rows, many_queue, "OWNER_INTERNAL", self.dir)
        self.assertEqual(3, len(result["files"]))
        self.assertEqual(30, len(json.loads((self.dir / "decisions-bulk-003.json").read_text(encoding="utf-8"))["items"]))

    def test_constants_match_the_backend(self):
        self.assertEqual(par.MAX_IMPORT, int(re.search(r"MAX_ITEMS = (\d+)", JAVA).group(1)))
        self.assertEqual(par.MAX_BULK, int(re.search(r"MAX_BULK = (\d+)", JAVA).group(1)))
        codes = re.findall(r'new Risk\("([A-Z_]+)"', JAVA)
        self.assertEqual({"NAME_MATCH_ONLY", "HOMONYM", "DISTINCT_PROPULSION", "VERSION_UNCONFIRMED", "MULTIPLE_CANDIDATES"}, set(codes))
        self.assertEqual(set(codes), {c for level in par.RISKS.values() for c in level})
        self.assertIn('List.of(NAME_MATCH, HOMONYM, DISTINCT_PROPULSION, VERSION_UNCONFIRMED)', JAVA)  # MODEL_ONLY
        self.assertEqual(["NAME_MATCH_ONLY", "HOMONYM", "DISTINCT_PROPULSION", "VERSION_UNCONFIRMED"], par.RISKS["MODEL_ONLY"])


if __name__ == "__main__":
    unittest.main()
