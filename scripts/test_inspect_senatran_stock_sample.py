import csv
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from inspect_senatran_stock_sample import HEADERS, inspect, quantity


class SenatranSampleTest(unittest.TestCase):
    def archive(self, directory, rows, headers=HEADERS):
        content = io.StringIO(newline="")
        writer = csv.writer(content, delimiter=";")
        writer.writerow(headers)
        writer.writerows(rows)
        path = Path(directory) / "source.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("source_Setembro_2026.TXT", content.getvalue().encode("utf-8-sig"))
        return path

    def test_literal_names_preserve_absence_unknown_years_and_distinct_aliases(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, [
                ["DF", "BRASILIA", "BEV/A", "2024", "2.0"],
                ["SP", "SAO PAULO", "BEV/A", "Sem Informação", "3"],
                ["SP", "SAO PAULO", "I/BEV A", "2025", "4"],
                ["DF", "BRASILIA", "BUS/A", "2010", "100"],
                ["DF", "BRASILIA", "ZERO", "2024", "0"],
            ])
            report = inspect(path, "2026-09", ["BEV/A", "I/BEV A", "MISSING", "ZERO"])
            samples = report["selected_literal_models"]
            self.assertEqual(109, report["all_registered_count"])
            self.assertEqual(5, samples["BEV/A"]["registered_count"])
            self.assertEqual(4, samples["I/BEV A"]["registered_count"])
            self.assertEqual(3, samples["BEV/A"]["by_manufacture_year"]["Sem Informação"])
            self.assertIsNone(samples["MISSING"]["registered_count"])
            self.assertEqual(0, samples["ZERO"]["registered_count"])
            self.assertNotIn("BUS/A", samples)
            self.assertEqual(2, samples["BEV/A"]["examples"][0]["line"])
            self.assertEqual(64, len(report["source_sha256"]))
            self.assertFalse(report["usableForE8"])
            self.assertIsNone(report["evr_total"])
            self.assertIsNone(report["weights"])

    def test_invalid_or_fractional_counts_fail_instead_of_rounding(self):
        for value in ["", "-1", "1.2", "NaN", "Infinity", "1,0", None]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                quantity(value, 7)
        self.assertEqual(12, quantity(" 12.0 ", 2))

    def test_other_schema_and_repeated_alias_selection_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, [], ["UF", "Município", "Combustível", "Quantidade"])
            with self.assertRaises(ValueError):
                inspect(path, "2026-09", ["BEV/A"])
            with self.assertRaises(ValueError):
                inspect(path, "2026-09", ["BEV/A", "BEV/A"])
            with self.assertRaises(ValueError):
                inspect(path, "2026-13", ["BEV/A"])
            with self.assertRaises(ValueError):
                inspect(path, "2025-09", ["BEV/A"])


if __name__ == "__main__":
    unittest.main()
