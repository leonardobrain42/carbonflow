import copy
import unittest
from datetime import date

from prepare_dy_evidence import prepare

CS, AF, AU = date(2023, 1, 1), date(2025, 1, 1), date(2026, 1, 1)
SHA = "a" * 64
UNI = {"decisionRef": "DEC-1", "decidedBy": "owner", "decidedOn": "2026-11-25", "programs": [{"program": "Prog A"}]}


def cons(kind, window, result="NO_RESULTS", **kw):
    s, u = (AF, AU) if window == "ANNUAL" else (CS, AF)
    c = {"program": "Prog A", "creditKind": kind, "window": window, "territory": "NATIONAL_BR", "result": result,
         "searchedFrom": s.isoformat(), "searchedUntilExclusive": u.isoformat(), "searchedOn": "2026-11-20",
         "sourceId": "reg", "sourceEdition": "2026-11", "locator": "filtro país=BR", "limitations": "consulta declarada",
         "captureSha256": SHA}
    c.update(kw)
    return c


def issuance(i, kind="ERC", on="2025-06-01", qty="10.5", **kw):
    d = {"program": "Prog A", "registryProject": "P1", "issuanceId": i, "creditKind": kind, "issuedOn": on, "geography": "BR",
         "creditsTco2e": qty, "sourceId": "reg", "sourceEdition": "2026-11", "locator": "serial 1-10"}
    d.update(kw)
    return d


def full(**ev):
    e = {"consultations": [cons(k, w) for k in ("ERC", "ERF") for w in ("ANNUAL", "CUMULATIVE")], "issuances": []}
    e.update(ev)
    return e


def run(evidence, declared=None, universe=UNI):
    return prepare(universe, evidence, CS, AF, AU, declared)


ZERO = {"annual": {"erc": "0", "erf": "0"}, "cumulative": {"erc": "0", "erf": "0"}}


class PrepareDyTest(unittest.TestCase):
    def test_no_default_universe(self):
        for bad in ({}, {**UNI, "decidedBy": ""}, {**UNI, "programs": []}):
            with self.assertRaises(ValueError):
                run(full(), universe=bad)
        with self.assertRaises(ValueError):
            prepare(UNI, full(), AF, CS, AU)

    def test_complete_negative_search_prepares_a_payload_with_declared_totals_only(self):
        out = run(full(), ZERO)
        self.assertTrue(out["annualCoverageComplete"])
        self.assertEqual([], out["blockers"])
        self.assertEqual("NOT_PROVEN", out["universe"]["exhaustive"])
        self.assertFalse(out["usableForE8"])
        self.assertEqual(2, len(out["payload"]["searchCoverage"]))
        self.assertIn(SHA, out["payload"]["searchCoverage"][0]["locator"])
        self.assertEqual("0", out["payload"]["erc"])
        self.assertIsNone(run(full())["payload"])  # no declared totals: never filled from the itemization

    def test_missing_cells_and_unlisted_programs_block_and_never_default(self):
        ev = full(); ev["consultations"] = [c for c in ev["consultations"] if not (c["creditKind"] == "ERF" and c["window"] == "ANNUAL")]
        out = run(ev, ZERO)
        self.assertFalse(out["annualCoverageComplete"])
        self.assertTrue(any("ERF" in b and "MISSING" in b for b in out["blockers"]))
        self.assertIsNone(out["payload"])
        ev = full(); ev["consultations"].append(cons("ERC", "ANNUAL", program="Outro"))
        out = run(ev, ZERO)
        self.assertTrue(any("OUTSIDE_UNIVERSE" in r["reason"] for r in out["refused"]))
        self.assertTrue(out["blockers"])

    def test_cell_states(self):
        for change, state in (({"territory": "PARTIAL_BR"}, "NOT_NATIONAL"), ({"result": "INCOMPLETE"}, "INCOMPLETE_RESULT"),
                              ({"searchedFrom": "2025-03-01"}, "PARTIAL_WINDOW"), ({"searchedFrom": "2024-06-01"}, "COVERS_WIDER_THAN_WINDOW"),
                              ({"searchedOn": "2025-12-01"}, "INCOHERENT_DATES")):
            ev = full(); ev["consultations"][0].update(change)
            first = run(ev)["coverageMatrix"][0]
            self.assertEqual(state, first["state"], change)
        ev = full(); ev["consultations"][0].update(searchedFrom="2024-06-01")
        self.assertFalse(run(ev)["annualCoverageComplete"])  # the API demands the exact window

    def test_found_needs_items_and_no_results_cannot_have_them(self):
        ev = full(); ev["consultations"][0]["result"] = "FOUND"
        self.assertEqual("FOUND_WITHOUT_ITEMS", run(ev)["coverageMatrix"][0]["state"])
        ev = full(issuances=[issuance("I1")])
        self.assertEqual("NO_RESULTS_WITH_ITEMS", run(ev)["coverageMatrix"][0]["state"])
        ev["consultations"][0]["result"] = "FOUND"
        out = run(ev, {"annual": {"erc": "10.5", "erf": "0"}, "cumulative": {"erc": "10.5", "erf": "0"}})
        self.assertEqual([], out["blockers"])
        self.assertEqual(1, len(out["payload"]["issuances"]))

    def test_items_split_by_window_and_reconcile_with_declared_totals(self):
        ev = full(issuances=[issuance("I1", on="2025-06-01", qty="10.5"), issuance("I2", on="2024-03-01", qty="4"),
                             issuance("I3", "ERF", on="2024-04-01", qty="1.25")])
        ev["consultations"][0]["result"] = "FOUND"; ev["consultations"][1]["result"] = "FOUND"
        ev["consultations"][2]["result"] = "FOUND"; ev["consultations"][3]["result"] = "FOUND"
        out = run(ev, {"annual": {"erc": "10.5", "erf": "0"}, "cumulative": {"erc": "14.5", "erf": "1.25"}})
        self.assertEqual({"erc": "14.5", "erf": "1.25"}, out["reconciliation"]["itemized"]["cumulative"])
        self.assertTrue(all(c["match"] for c in out["reconciliation"]["checks"]))
        wrong = run(ev, {"annual": {"erc": "10", "erf": "0"}, "cumulative": {"erc": "14.5", "erf": "1.25"}})
        self.assertTrue(any("difere dos itens" in b for b in wrong["blockers"]))
        self.assertIsNone(wrong["payload"])

    def test_item_refusals(self):
        for item, needle in ((issuance("X", on="2026-02-01"), "fora de"), (issuance("X", on="2022-12-31"), "fora de"),
                             (issuance("X", geography="US"), "BR"), (issuance("X", qty="0"), "positivo"),
                             (issuance("X", qty=3), "positivo"), (issuance("X", kind="VCU"), "ERC ou ERF"),
                             (issuance("X", locator=" "), "locator"), (issuance("X", program="Zzz"), "OUTSIDE_UNIVERSE"),
                             (issuance("X", sourceEdition=""), "edição")):
            out = run(full(issuances=[item]))
            self.assertIn(needle, " ".join(r["reason"] for r in out["refused"]), item)
        out = run(full(issuances=[issuance("D"), issuance("d")]))
        self.assertIn("duplicada", out["refused"][0]["reason"])

    def test_capture_gaps_are_listed_not_hidden(self):
        ev = full(); ev["consultations"][0].pop("captureSha256"); ev["consultations"][1]["captureSha256"] = "xyz"
        out = run(ev, ZERO)
        self.assertEqual(2, len(out["captureGaps"]))
        self.assertEqual([], out["blockers"])  # a missing capture is a gap to review, not a computed block

    def test_cumulative_coverage_is_informational_and_never_in_the_payload(self):
        ev = full(); ev["consultations"] = [c for c in ev["consultations"] if c["window"] == "ANNUAL"]
        out = run(ev, ZERO)
        self.assertTrue(out["annualCoverageComplete"])
        self.assertFalse(out["cumulativeCoverageInformational"]["complete"])
        self.assertFalse(out["cumulativeCoverageInformational"]["carriedByApi"])
        self.assertEqual([], out["blockers"])
        self.assertNotIn("CUMULATIVE", str(out["payload"]["searchCoverage"]))


if __name__ == "__main__":
    unittest.main()
