import csv
import json
import re
import tempfile
import unittest
from pathlib import Path

import prepare_stock_ingestion as psi

ROOT = Path(__file__).resolve().parent.parent
ORG, YEAR = "org-1", 2026


def model(id_, cls, prop, name="DOLPHIN", variant="GS"):
    return {"id": id_, "manufacturer": "BYD", "modelName": name, "variant": variant, "vehicleClass": cls, "propulsion": prop}


CATALOG = {"models": [model("m1", "LDV", "BEV"), model("m2", "UNKNOWN", "UNKNOWN", "SEAL"), model("m3", "LDV", "PHEV", "SONG"),
                      model("m4", "LDV", "BEV", "MINI")],
           "aliases": [{"organizationId": ORG, "observationYear": YEAR, "recordKey": k, "modelId": m} for k, m in [
               ("A_DOLPHIN", "m1"), ("B_DOLPHIN", "m1"), ("SEAL_1", "m2"), ("SONG_1", "m3"), ("MINI_1", "m4")]]
           + [{"organizationId": "other", "observationYear": YEAR, "recordKey": "NO_ALIAS_HERE", "modelId": "m1"}]}


class StockTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def stock(self, rows):
        path = self.dir / "stock.csv"
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.writer(stream, lineterminator="\n")
            writer.writerow(psi.COLUMNS)
            for key, value in rows:
                writer.writerow([key, YEAR, "stock", value, "vehicles", "BR"])
        return path

    def test_every_vehicle_is_accounted_and_names_of_one_model_are_summed(self):
        path = self.stock([("a_dolphin", 100), ("B_DOLPHIN", 50), ("MINI_1", 7), ("SEAL_1", 30), ("SONG_1", 20), ("SEM_ALIAS", 5), ("NO_ALIAS_HERE", 9)])
        report = psi.run(path, CATALOG, ORG, YEAR, self.dir / "out")
        t = report["buckets"]
        self.assertEqual({"rows": 3, "vehicles": 157}, t["INCLUDED"])
        self.assertEqual({"rows": 1, "vehicles": 20}, t["OUTSIDE_SCOPE"])
        self.assertEqual({"rows": 1, "vehicles": 30}, t["PENDING_CLASSIFICATION"])
        self.assertEqual({"rows": 2, "vehicles": 14}, t["NO_ALIAS"])  # alias of ANOTHER organization does not count
        self.assertEqual(sum(b["vehicles"] for b in t.values()), report["input"]["vehicles"])
        rows = list(csv.DictReader((self.dir / "out/stock-ingestion-001.csv").open(encoding="utf-8")))
        self.assertEqual([("A_DOLPHIN", "150"), ("MINI_1", "7")], [(r["record_key"], r["value"]) for r in rows])  # one row per model
        self.assertEqual(2, len({r["record_key"] for r in rows}))
        dolphin = report["composition"][0]
        self.assertEqual([{"key": "A_DOLPHIN", "vehicles": 100}, {"key": "B_DOLPHIN", "vehicles": 50}], dolphin["sources"])
        self.assertEqual(report["output"]["vehicles"], t["INCLUDED"]["vehicles"])

    def test_never_claims_national_denominator_coverage_or_e8(self):
        report = psi.run(self.stock([("A_DOLPHIN", 1)]), CATALOG, ORG, YEAR, self.dir / "out")
        self.assertEqual((False, "UNKNOWN", False, False), (report["nationalDenominatorClaimed"], report["coverage"],
                                                             report["usableForE8"], report["complete"]))
        self.assertTrue(any("não prova circulação" in n for n in report["notes"]))
        self.assertEqual(64, len(report["input"]["sha256"]))
        self.assertEqual(psi.sha256(self.dir / "out/stock-ingestion-001.csv"), report["output"]["sha256"])

    def test_empty_recorte_is_a_gap_not_zero(self):
        report = psi.run(self.stock([("SEAL_1", 30), ("SEM_ALIAS", 5)]), CATALOG, ORG, YEAR, self.dir / "out")
        self.assertEqual(0, report["output"]["rows"])
        self.assertTrue(any("ausência não equivale a zero" in g for g in report["gaps"]))
        self.assertTrue(any("sem alias" in g for g in report["gaps"]))

    def test_invalid_input_is_refused(self):
        for rows in ([("A_DOLPHIN", 1), ("a_dolphin", 2)], [("A_DOLPHIN", -1)]):
            with self.assertRaises(psi.StockError):
                psi.run(self.stock(rows), CATALOG, ORG, YEAR, self.dir / "out")
        path = self.dir / "bad.csv"
        path.write_text("record_key,value\nX,1\n", encoding="utf-8")
        with self.assertRaises(psi.StockError):
            psi.run(path, CATALOG, ORG, YEAR, self.dir / "out")
        path.write_text(",".join(psi.COLUMNS) + "\nX,2026,stock,1.5,vehicles,BR\n", encoding="utf-8")
        with self.assertRaises(psi.StockError):  # rounding is forbidden
            psi.run(path, CATALOG, ORG, YEAR, self.dir / "out")
        path.write_text(",".join(psi.COLUMNS) + "\nX,2025,stock,1,vehicles,BR\n", encoding="utf-8")
        with self.assertRaises(psi.StockError):  # mixed years
            psi.run(path, CATALOG, ORG, YEAR, self.dir / "out")

    def test_limit_matches_the_backend(self):
        java = (ROOT / "backend/src/main/java/br/carbonflow/pilot/TabularCandidateParser.java").read_text(encoding="utf-8")
        self.assertEqual(psi.MAX_ROWS, int(re.search(r"MAX_ROWS = (\d+)", java).group(1)))
        many = {"models": [model(f"m{i}", "LDV", "BEV", f"M{i}") for i in range(psi.MAX_ROWS + 1)],
                "aliases": [{"organizationId": ORG, "observationYear": YEAR, "recordKey": f"K{i}", "modelId": f"m{i}"}
                            for i in range(psi.MAX_ROWS + 1)]}
        with self.assertRaises(psi.StockError):
            psi.run(self.stock([(f"K{i}", 1) for i in range(psi.MAX_ROWS + 1)]), many, ORG, YEAR, self.dir / "out")


if __name__ == "__main__":
    unittest.main()
