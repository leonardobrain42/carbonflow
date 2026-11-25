import csv
import io
import tempfile
import unittest
import zipfile
from pathlib import Path

from build_evr_stock_csv import KEY_PATTERN, MAX_ROWS, build, key_for
from inspect_senatran_stock_sample import HEADERS


class EvrBridgeTest(unittest.TestCase):
    def archive(self, directory, rows):
        content = io.StringIO(newline="")
        writer = csv.writer(content, delimiter=";")
        writer.writerow(HEADERS)
        writer.writerows(rows)
        path = Path(directory) / "source.zip"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("source_Setembro_2026.TXT", content.getvalue().encode("utf-8-sig"))
        return path

    def test_rows_use_observation_year_registered_counts_and_parser_valid_keys(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, [
                ["DF", "BRASILIA", "I/BEV A", "2024", "2"],
                ["SP", "SAO PAULO", "I/BEV A", "2025", "3"],
                ["DF", "BRASILIA", "BYD/MINI", "2024", "7"],
                ["DF", "BRASILIA", "OUTRO", "2024", "1000"],
            ])
            rows, manifest = build(path, "2026-09", ["I/BEV A", "BYD/MINI"])
        self.assertEqual([["I_BEV_A", "2026", "stock", "5", "vehicles", "BR"],
                          ["BYD_MINI", "2026", "stock", "7", "vehicles", "BR"]], rows)
        for row in rows:
            self.assertRegex(row[0], KEY_PATTERN)
        self.assertEqual({"I_BEV_A": "I/BEV A", "BYD_MINI": "BYD/MINI"}, manifest["key_to_literal_name"])
        # Unselected models never leak into the output or the totals of the CSV.
        self.assertNotIn("OUTRO", manifest["key_to_literal_name"].values())
        self.assertEqual(1012, manifest["all_registered_count"])

    def test_absence_stays_absent_and_zero_stays_zero(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, [["DF", "BRASILIA", "ZERO", "2024", "0"]])
            rows, manifest = build(path, "2026-09", ["ZERO", "MISSING"])
        self.assertEqual([["ZERO", "2026", "stock", "0", "vehicles", "BR"]], rows)
        self.assertEqual(["MISSING"], manifest["literal_names_without_rows"])

    def test_colliding_or_invalid_keys_are_refused_instead_of_merged(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, [["DF", "BRASILIA", "A/B", "2024", "1"], ["DF", "BRASILIA", "A B", "2024", "1"]])
            with self.assertRaisesRegex(ValueError, "mesma chave"):
                build(path, "2026-09", ["A/B", "A B"])
        with self.assertRaises(ValueError):
            key_for("   ")
        with self.assertRaises(ValueError):
            key_for("X" * 101)

    def test_manifest_never_admits_anything(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.archive(directory, [["DF", "BRASILIA", "BEV", "2024", "1"]])
            _, manifest = build(path, "2026-09", ["BEV"])
        self.assertFalse(manifest["usableForE8"])
        self.assertIsNone(manifest["evr_total"])
        self.assertIsNone(manifest["weights"])
        self.assertEqual("REGISTERED_VEHICLES", manifest["metric_meaning"])
        self.assertTrue(any("não é estoque em circulação" in text for text in manifest["limitations"]))

    def test_the_parser_row_limit_is_respected(self):
        self.assertEqual(2000, MAX_ROWS)


if __name__ == "__main__":
    unittest.main()
