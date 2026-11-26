import tempfile
import unittest
from pathlib import Path

import grid_time_hypotheses as g
from test_import_mcti_hourly import build


class HypothesesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = Path(self.dir.name) / "synthetic.xlsx"
        build(self.path, days=3)

    def run_analysis(self, hypotheses, window=None):
        return g.analyse(self.path, "tCO2/MWh", hypotheses, window)[0]

    def pair(self, result, a, b):
        return next(p for p in result["pairs"] if p["a"] == a and p["b"] == b)

    def test_identical_hypotheses_do_not_differ(self):
        series = g.load(self.path, "tCO2/MWh", "UTC:HOUR_ENDING_1_24")
        values = g.values_by_instant(series, None)
        result = g.compare(values, dict(values))
        self.assertEqual(result["differing"], 0)
        self.assertGreater(result["commonInstants"], 0)

    def test_hour_convention_shifts_the_value_at_the_same_instant(self):
        result = self.run_analysis(["UTC:HOUR_ENDING_1_24", "UTC:HOUR_START_LABEL_1_24"])
        pair = self.pair(result, "UTC:HOUR_ENDING_1_24", "UTC:HOUR_START_LABEL_1_24")
        self.assertGreater(pair["differing"], 0)
        self.assertGreater(Decimal(pair["maxAbsDifference"]), 0)

    def test_zone_shifts_the_value_at_the_same_instant(self):
        result = self.run_analysis(["UTC:HOUR_ENDING_1_24", "America/Sao_Paulo:HOUR_ENDING_1_24"])
        self.assertGreater(result["pairs"][0]["differing"], 0)

    def test_never_picks_a_winner_and_marks_all_as_set_by_people(self):
        result = self.run_analysis(g.DEFAULTS)
        self.assertEqual(len(result["hypotheses"]), 4)
        self.assertTrue(all(h["setByPeople"] for h in result["hypotheses"]))
        self.assertNotIn("recommended", str(result).lower())
        self.assertIn("nenhuma é apontada como correta", result["notice"])

    def test_window_restricts_the_comparison(self):
        window = (g.parse_instant("2026-01-02T00:00:00Z"), g.parse_instant("2026-01-02T06:00:00Z"))
        result = self.run_analysis(["UTC:HOUR_ENDING_1_24", "UTC:HOUR_START_LABEL_1_24"], window)
        self.assertEqual(result["hypotheses"][0]["instantsInWindow"], 6)

    def test_invalid_hypothesis_stops(self):
        with self.assertRaises(g.importer.ImportErrorStop):
            self.run_analysis(["Marte/Base:HOUR_ENDING_1_24"])


from decimal import Decimal  # noqa: E402

if __name__ == "__main__":
    unittest.main()
