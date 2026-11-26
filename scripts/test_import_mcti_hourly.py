import json
import re
import tempfile
import unittest
import zipfile
from pathlib import Path

from import_mcti_hourly import ImportErrorStop, importer

MONTHS = ["Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho", "Julho", "Agosto", "Setembro", "Outubro",
          "Novembro", "Dezembro"]


def cell(ref, value):
    if isinstance(value, str) and not re.fullmatch(r"\d+(\.\d+)?", value):
        return f'<c r="{ref}" t="inlineStr"><is><t>{value}</t></is></c>'
    return f'<c r="{ref}" t="n"><v>{value}</v></c>'


def build(path, *, year=2026, title="Fator de Emissão Médio (tCO2/MWh) - HORÁRIO", months=(1, 2), days=None,
          extra=None, skip_hour=None, decoy=True):
    """Synthetic workbook shaped like the real one: title, year row, Dia/Hora/months header, day label only on hour 1."""
    letters = "CDEFGHIJKLMN"
    rows = []
    if decoy:
        rows.append('<row r="1">' + cell("F1", "Fator de Emissão Médio (tCO2/MWh) - MENSAL") + "</row>")
    rows.append('<row r="3">' + cell("F3", title) + "</row>")
    rows.append('<row r="4">' + cell("A4", year) + cell("H4", "MÊS") + "</row>")
    rows.append('<row r="5">' + cell("A5", "Dia") + cell("B5", "Hora") +
                "".join(cell(f"{letters[i]}5", m) for i, m in enumerate(MONTHS)) + "</row>")
    r = 6
    import calendar
    max_day = days or 31
    for d in range(1, max_day + 1):
        for h in range(1, 25):
            if skip_hour == (d, h):
                continue
            cs = []
            if h == 1:
                cs.append(cell(f"A{r}", d))
            cs.append(cell(f"B{r}", h))
            for m in months:
                if d <= calendar.monthrange(year, m)[1]:
                    cs.append(cell(f"{letters[m - 1]}{r}", f"0.{d:02d}{h:02d}0"))
            for ref, v in (extra or {}).get((d, h), []):
                cs.append(cell(ref.format(r=r), v))
            rows.append(f'<row r="{r}">' + "".join(cs) + "</row>")
            r += 1
    sheet = ('<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
             + "".join(rows) + "</sheetData></worksheet>")
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("xl/workbook.xml", '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                   'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'
                   '<sheet name="Despacho" sheetId="1" r:id="rId1"/></sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                   '<Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>')
        z.writestr("xl/worksheets/sheet1.xml", sheet)


class ImporterTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.path = Path(self.dir.name) / "synthetic.xlsx"

    def run_import(self, **kw):
        params = dict(timezone="UTC", hour_convention="HOUR_ENDING_1_24", unit="tCO2/MWh")
        params.update(kw)
        return importer(self.path, **params)

    def test_preserves_original_locators_values_and_labels(self):
        build(self.path)
        out = self.run_import()
        first = out["records"][0]
        self.assertEqual(("C6", 1, 1, 1, "0.01010"), (first["cell"], first["month"], first["day"], first["hourLabel"], first["valueOriginal"]))
        self.assertEqual("0.01010", first["value"])  # identity unit keeps the text exactly
        self.assertEqual(64, len(out["original"]["fileSha256"]))
        self.assertEqual(("Despacho", 2026, "tCO2/MWh", "CO2"),
                         (out["original"]["sheet"], out["original"]["year"], out["original"]["unitAsDeclaredInTitle"], out["original"]["gas"]))
        # the MENSAL decoy was not mistaken for the hourly table; months 1 and 2 only, 28 days in 2026-02
        self.assertEqual(31 * 24 + 28 * 24, out["quality"]["recordCount"])
        self.assertEqual("COMPLETE", out["quality"]["months"]["1"]["state"])
        self.assertEqual("NOT_PUBLISHED", out["quality"]["months"]["3"]["state"])

    def test_hour_conventions_change_the_interval_and_nothing_is_defaulted(self):
        build(self.path, months=(1,))
        end = self.run_import()["records"][-1]       # day 31, label 24
        self.assertEqual(("2026-01-31T23:00:00+00:00", "2026-02-01T00:00:00+00:00"), (end["intervalStartLocal"], end["intervalEndLocal"]))
        start = self.run_import(hour_convention="HOUR_START_LABEL_1_24")["records"][-1]
        self.assertEqual("2026-02-01T00:00:00+00:00", start["intervalStartLocal"])
        self.assertEqual("2026-01-01T01:00:00+00:00", self.run_import(hour_convention="HOUR_START_LABEL_1_24")["records"][0]["intervalStartLocal"])
        with self.assertRaises(ImportErrorStop):
            self.run_import(hour_convention="WHATEVER")
        with self.assertRaises(ImportErrorStop):
            self.run_import(timezone="Mars/Olympus")

    def test_timezone_offsets_apply_and_dst_gaps_and_folds_are_flagged_not_guessed(self):
        build(self.path, months=(3, 11), days=31)
        br = self.run_import(timezone="America/Sao_Paulo")["records"][0]
        self.assertEqual("2026-03-01T00:00:00-03:00", br["intervalStartLocal"])
        self.assertEqual("2026-03-01T03:00:00Z", br["intervalStartUtc"])
        ny = self.run_import(timezone="America/New_York")["records"]
        gap = [x for x in ny if x["timeIssue"] == "LOCAL_TIME_NONEXISTENT"]
        fold = [x for x in ny if x["timeIssue"] == "LOCAL_TIME_AMBIGUOUS"]
        self.assertTrue(gap and fold)
        self.assertTrue(all(x["intervalStartUtc"] is None or x["intervalEndUtc"] is None for x in gap + fold))
        self.assertTrue(any(x["intervalStartLocal"].startswith("2026-03-08T02") for x in gap))
        self.assertTrue(any(x["intervalStartLocal"].startswith("2026-11-01T01") for x in fold))

    def test_unit_must_match_the_title_and_target_conversion_is_exact(self):
        build(self.path, months=(1,))
        with self.assertRaises(ImportErrorStop):
            self.run_import(unit="kgCO2/MWh")
        out = self.run_import(target_unit="kgCO2/MWh")
        self.assertEqual(("10.10000", "kgCO2/MWh", "0.01010"), (out["records"][0]["value"], out["records"][0]["unit"], out["records"][0]["valueOriginal"]))
        with self.assertRaises(ImportErrorStop):
            self.run_import(target_unit="tCO2e/MWh")

    def test_year_mismatch_and_broken_structure_stop_the_import(self):
        build(self.path, months=(1,))
        with self.assertRaises(ImportErrorStop):
            self.run_import(year=2025)
        build(self.path, months=(1,), skip_hour=(3, 7))
        with self.assertRaises(ImportErrorStop) as ctx:
            self.run_import()
        self.assertIn("fora de sequência", str(ctx.exception))

    def test_anomalies_are_listed_and_never_imported(self):
        build(self.path, months=(2,), extra={(30, 5): [("D{r}", "0.5")], (2, 2): [("C{r}", "abc")]}, days=31)
        out = self.run_import()
        codes = sorted(i["code"] for i in out["quality"]["issues"])
        self.assertEqual(["NON_NUMERIC_VALUE", "VALUE_ON_NONEXISTENT_DAY"], codes[:1] + codes[-1:])
        self.assertTrue(all(r["day"] <= 28 for r in out["records"] if r["month"] == 2))
        self.assertFalse(any(r["valueOriginal"] == "abc" for r in out["records"]))

    def test_output_never_claims_admission_rights_or_co2e(self):
        build(self.path, months=(1,))
        out = self.run_import()
        self.assertFalse(out["usableForE8"])
        self.assertEqual(("UNCONFIRMED", "NOT_EVALUATED", "NOT_ASSUMED"), (out["rights"], out["eligibilityVT0011Tool07"], out["co2eEquivalence"]))
        json.dumps(out)

    def test_ambiguous_or_missing_table_marker_stops(self):
        build(self.path, title="Fator de Emissão Médio (tCO2/MWh) - MENSAL", decoy=True)
        with self.assertRaises(ImportErrorStop):
            self.run_import()


if __name__ == "__main__":
    unittest.main()
