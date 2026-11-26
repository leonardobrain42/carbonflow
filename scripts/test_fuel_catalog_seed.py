import json
import pathlib
import re
import subprocess
import sys
import unittest
from decimal import Decimal

import build_fuel_catalog_seed as b

ROOT = pathlib.Path(__file__).resolve().parent.parent
SEED = json.loads((ROOT / "docs/data/fuel-catalog-br-seed.json").read_text(encoding="utf-8"))
UNIT = re.compile(r"(t|kg|g)(CO2e|CO2|CH4|N2O)?/(MJ|GJ|TJ|L|USgal|kg|m3)")


class SeedTest(unittest.TestCase):
    def test_published_file_matches_the_generator(self):
        self.assertEqual(subprocess.run([sys.executable, str(ROOT / "scripts/build_fuel_catalog_seed.py"), "--check"]).returncode, 0)

    def test_gasoline_c_is_derived_with_exact_arithmetic(self):
        value, fraction = b.derived_gasoline_c()
        self.assertEqual(fraction, Decimal("0.68"))
        self.assertEqual(value, Decimal("1504.143816"))
        entry = next(e for e in SEED["entries"] if e["fuelId"] == "GASOLINE_C" and e.get("parameter") == "EMISSION_FACTOR")
        self.assertEqual(Decimal(entry["value"]), value)
        self.assertTrue(entry["evidenceLocator"].startswith("DERIVADO"))

    def test_every_entry_cites_a_document_that_was_read_and_is_not_reviewed(self):
        for e in SEED["entries"]:
            self.assertIn(e["readState"], {"PRIMARY_READ", "SECONDARY"})
            self.assertRegex(e["evidenceSha256"], r"^[0-9a-f]{64}$")
            self.assertLessEqual(len(e["evidenceLocator"]), 300)
            self.assertNotIn("review", json.dumps(e).lower().replace("reviewed", ""))  # nothing claims a review
            if e.get("parameter") == "EMISSION_FACTOR":
                self.assertRegex(e["unit"], UNIT)

    def test_derived_and_secondary_values_are_not_marked_primary(self):
        for e in SEED["entries"]:
            if e["evidenceSource"].startswith("AEA"):
                self.assertEqual(e["readState"], "SECONDARY")

    def test_biogenic_co2_is_registered_but_apart_from_the_fossil_factor(self):
        gases = {(e["fuelId"], e.get("gas")) for e in SEED["entries"] if e.get("parameter") == "EMISSION_FACTOR"}
        self.assertIn(("ETHANOL_ANHYDROUS", "CO2_BIOGENIC"), gases)
        self.assertIn(("ETHANOL_ANHYDROUS", "CO2_FOSSIL"), gases)
        self.assertNotIn(("GASOLINE_C", "CO2_UNSPECIFIED"), gases)

    def test_composition_window_follows_the_cnpe_act(self):
        comps = [e for e in SEED["entries"] if e["kind"] == "COMPOSITION"]
        self.assertEqual({c["validFrom"] for c in comps}, {"2026-08-01"})
        self.assertEqual({c["validTo"] for c in comps}, {"2027-01-27"})
        self.assertEqual(sum(Decimal(c["nominal"]) for c in comps if c.get("nominal")), Decimal("0.32"))


if __name__ == "__main__":
    unittest.main()
