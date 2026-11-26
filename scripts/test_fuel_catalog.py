import copy
import json
import unittest
from pathlib import Path

from fuel_catalog import validate

SEED = Path(__file__).resolve().parent.parent / "docs" / "data" / "fuel-catalog.json"
EV = {"source": "fonte sintética", "locator": "tabela 1", "readState": "PRIMARY_READ"}


def base():
    return {"fuels": [{"id": "A", "family": "GASOLINE", "role": "PRODUCT", "phase": "LIQUID"},
                      {"id": "E", "family": "ETHANOL", "role": "PRODUCT", "phase": "LIQUID"},
                      {"id": "C", "family": "GASOLINE", "role": "BLEND", "phase": "LIQUID"}],
            "compositions": [], "data": []}


def point(**kw):
    d = {"fuelId": "A", "parameter": "EMISSION_FACTOR", "emissionScope": "TTW", "gas": "CO2_FOSSIL", "value": "1.5", "unit": "kg/L", "vintageYear": 2024,
         "validFrom": "2024-01-01", "validTo": "2024-12-31", "evidence": dict(EV),
         "review": {"basis": "NONE"}, "admission": "NOT_ADMITTED"}
    d.update(kw)
    return d


def comp(component, nominal=None, remainder=False, **kw):
    c = {"blendId": "C", "componentId": component, "basis": "VOLUME_FRACTION", "validFrom": "2026-08-01", "validTo": None, "evidence": dict(EV)}
    c.update({"remainder": True} if remainder else {"nominal": nominal})
    c.update(kw)
    return c


class FuelCatalogTest(unittest.TestCase):
    def errors(self, catalog):
        return " | ".join(validate(catalog))

    def test_the_versioned_seed_is_valid_and_holds_no_factor(self):
        seed = json.loads(SEED.read_text(encoding="utf-8"))
        self.assertEqual([], validate(seed))
        self.assertEqual([], seed["data"])
        self.assertEqual(8, len(seed["fuels"]))
        self.assertTrue(all(f["pending"] for f in seed["fuels"]))
        self.assertTrue(all(c["evidence"]["readState"] == "PRIMARY_READ" for c in seed["compositions"]))

    def test_a_complete_synthetic_point_is_valid_and_never_admitted(self):
        cat = base(); cat["data"].append(point())
        self.assertEqual([], validate(cat))
        cat["data"][0]["admission"] = "ADMITTED"
        self.assertIn("NOT_ADMITTED", self.errors(cat))

    def test_gas_unit_and_gwp_are_explicit(self):
        for change, needle in (({"gas": None}, "gás obrigatório"), ({"gas": "CO2E_AGGREGATE"}, "gwpSet explícito"),
                               ({"gas": "CO2_FOSSIL", "gwpSet": "AR5_100"}, "só vale para CO2E"), ({"emissionScope": None}, "escopo"),
                               ({"unit": "MJ/kg"}, "não pertence à dimensão"), ({"value": "-1"}, "não negativo"),
                               ({"value": 1.5}, "não negativo"), ({"vintageYear": 1800}, "vintage")):
            cat = base(); cat["data"].append(point(**change))
            self.assertIn(needle, self.errors(cat), change)
        ok = base(); ok["data"].append(point(gas="CO2E_AGGREGATE", gwpSet="AR5_100", unit="tCO2e/USgal"))
        self.assertEqual([], validate(ok))
        prop = base(); prop["data"].append(point(parameter="LHV", gas=None, emissionScope=None, unit="MJ/kg"))
        self.assertEqual([], validate(prop))
        prop["data"][0]["gas"] = "CO2_FOSSIL"
        self.assertIn("gás só vale", self.errors(prop))

    def test_evidence_is_mandatory_and_unread_primary_carries_no_value(self):
        cat = base(); cat["data"].append(point(evidence={"source": "x", "readState": "SECONDARY"}))
        self.assertIn("fonte e localizador", self.errors(cat))
        cat = base(); cat["data"].append(point(evidence={**EV, "readState": "PRIMARY_NOT_READ"}))
        self.assertIn("não lida não sustenta valor", self.errors(cat))
        cat = base(); cat["data"].append(point(evidence=None))
        self.assertIn("evidência ausente", self.errors(cat))

    def test_review_bases_are_not_interchangeable(self):
        cat = base(); cat["data"].append(point(review={"basis": "INDEPENDENT", "registeredBy": "a", "reviewedBy": "a"}))
        self.assertIn("revisor diferente", self.errors(cat))
        cat = base(); cat["data"].append(point(review={"basis": "OWNER_INTERNAL"}))
        self.assertIn("sem revisor", self.errors(cat))
        cat = base(); cat["data"].append(point(review={"basis": "INDEPENDENT", "registeredBy": "a", "reviewedBy": "b"}))
        self.assertEqual([], validate(cat))

    def test_validity_windows_of_a_series_cannot_overlap(self):
        cat = base()
        cat["data"] += [point(), point(value="1.6", validFrom="2024-12-31", validTo=None, vintageYear=2025)]
        self.assertIn("se sobrepõem", self.errors(cat))
        cat["data"][1]["validFrom"] = "2025-01-01"
        self.assertEqual([], validate(cat))
        cat["data"][1]["gas"] = "CH4"  # another gas is another series
        cat["data"][1]["validFrom"] = "2024-06-01"
        self.assertEqual([], validate(cat))
        cat["data"][0]["validTo"] = "2023-01-01"
        self.assertIn("anterior a validFrom", self.errors(cat))

    def test_blend_composition_sums_to_exactly_one(self):
        cat = base(); cat["compositions"] = [comp("E", "0.32"), comp("A", remainder=True)]
        self.assertEqual([], validate(cat))
        cat["compositions"] = [comp("E", "0.32"), comp("A", "0.67")]
        self.assertIn("não 1", self.errors(cat))
        cat["compositions"] = [comp("E", "1.2"), comp("A", remainder=True)]
        self.assertIn("[0,1]", self.errors(cat))
        cat["compositions"] = [comp("E", "0.4"), comp("A", remainder=True), comp("A", remainder=True)]
        self.assertIn("mais de um remainder", self.errors(cat))
        cat["compositions"] = [comp("E", "0.32", blendId="A"), comp("A", remainder=True, blendId="A")]
        self.assertIn("não é BLEND", self.errors(cat))

    def test_unknown_fuel_and_duplicate_ids_are_refused(self):
        cat = base(); cat["data"].append(point(fuelId="ZZZ"))
        self.assertIn("combustível inexistente", self.errors(cat))
        cat = base(); cat["fuels"].append(copy.deepcopy(cat["fuels"][0]))
        self.assertIn("repetidos", self.errors(cat))


if __name__ == "__main__":
    unittest.main()
