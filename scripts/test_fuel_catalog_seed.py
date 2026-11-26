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

    def test_derived_entries_are_labelled_and_the_ipcc_comparison_uses_the_same_unit(self):
        derived = [e for e in SEED["entries"] if e["evidenceLocator"].startswith("DERIVADO")]
        self.assertEqual(len(derived), 2)  # gasolina C E32 and E30
        compared = [e for e in SEED["entries"] if "ipccValue" in e]
        self.assertEqual(len(compared), 1)
        self.assertEqual((compared[0]["fuelId"], compared[0]["unit"], compared[0]["ipccUnit"]), ("GASOLINE_A", "gCO2/kg", "gCO2/kg"))
        self.assertEqual(Decimal(compared[0]["ipccValue"]), Decimal("69300") * Decimal("44.3") / 1000)
        self.assertIn("sem refinamento em 2019", compared[0]["ipccReference"])

    def test_sources_are_primary_documents_that_were_read(self):
        self.assertEqual({e["readState"] for e in SEED["entries"]}, {"PRIMARY_READ"})
        self.assertTrue(all("AEA" not in e["evidenceSource"] for e in SEED["entries"]))

    def test_e30_and_e32_windows_do_not_overlap_and_each_sums_to_one(self):
        comps = [e for e in SEED["entries"] if e["kind"] == "COMPOSITION"]
        windows = {(c["validFrom"], c["validTo"]) for c in comps}
        self.assertEqual(windows, {("2025-08-01", "2026-07-31"), ("2026-08-01", "2027-01-27")})
        for w, share in ((("2025-08-01", "2026-07-31"), "0.30"), (("2026-08-01", "2027-01-27"), "0.32")):
            self.assertEqual(sum(Decimal(c["nominal"]) for c in comps if (c["validFrom"], c["validTo"]) == w and c.get("nominal")), Decimal(share))
        e30 = next(e for e in SEED["entries"] if e["fuelId"] == "GASOLINE_C" and e.get("parameter") == "EMISSION_FACTOR" and e["validFrom"] == "2025-08-01")
        self.assertEqual(Decimal(e30["value"]), Decimal("0.70") * Decimal("0.742") * Decimal("2981.1"))

    def test_biogenic_co2_is_registered_but_apart_from_the_fossil_factor(self):
        gases = {(e["fuelId"], e.get("gas")) for e in SEED["entries"] if e.get("parameter") == "EMISSION_FACTOR"}
        self.assertIn(("ETHANOL_ANHYDROUS", "CO2_BIOGENIC"), gases)
        self.assertIn(("ETHANOL_ANHYDROUS", "CO2_FOSSIL"), gases)
        self.assertNotIn(("GASOLINE_C", "CO2_UNSPECIFIED"), gases)


if __name__ == "__main__":
    unittest.main()
