import json
import tempfile
import unittest
from pathlib import Path

from extract_pbev_electric import extract, parse


def row(category, brand, model, version, tail="A  S  E  E"):
    cells = [category, brand, model] + ([version] if version else []) + ["Elétrico", "Elétrico", tail]
    return "      " + "          ".join(cells)


HEADER = "   Elétricos (VE)       3     Modelos\n"
NOISE = [
    "      Categoria       Marca     Modelo     Versão     Motor    Combustão    Híbrido",
    "                                                   Plug-in     Elétrico (MTA)",
    "      Elétrico Automática Dupla (M) (G) Diesel",
]


class PbevExtractorTest(unittest.TestCase):
    def pdf(self, directory):
        path = Path(directory) / "pbev.pdf"
        path.write_bytes(b"%PDF-1.4 fake")
        return path

    def test_rows_keep_printed_fields_and_headers_are_not_rows(self):
        text = HEADER + "\n".join(NOISE + [
            row("Sub Compacto", "BYD", "DOLPHIN MINI", "GS 5 EV"),
            row("Extra Grande", "PORSCHE", "CAYENNE", "E"),
            row("Comercial", "RENAULT", "E-KWID", None),
        ])
        rows, header, skipped = parse(text)
        self.assertEqual(3, header)
        self.assertEqual([], skipped)
        self.assertEqual(("Sub Compacto", "BYD", "DOLPHIN MINI", "GS 5 EV"),
                         (rows[0]["category"], rows[0]["brand"], rows[0]["model"], rows[0]["version"]))
        self.assertIsNone(rows[2]["version"])  # absence stays absence, never a guess
        self.assertEqual({"ELETRICO"}, {r["propulsion"] for r in rows})

    def test_unexpected_layout_is_listed_not_guessed(self):
        text = HEADER + "\n".join([
            row("Médio", "BYD", "DOLPHIN", "GS 180EV"),
            "      Elétrico          Elétrico          A  S",  # a fragment with no leading fields
            "      A B C D E          Elétrico          Elétrico          A",  # too many leading fields
        ])
        rows, _, skipped = parse(text)
        self.assertEqual(1, len(rows))
        self.assertEqual([3, 4], sorted([s["line"] for s in skipped]))
        self.assertTrue(all("não são 3 ou 4" in s["reason"] for s in skipped))

    def test_count_is_compared_with_the_header_and_mismatch_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            pdf = self.pdf(directory)
            ok = extract(pdf, HEADER + "\n".join([row("Médio", "A", "B", "C"), row("Grande", "D", "E", "F"),
                                                  row("Grande", "G", "H", "I")]))
            self.assertTrue(ok["count_matches_header"])
            bad = extract(pdf, HEADER + row("Médio", "A", "B", "C"))
            self.assertFalse(bad["count_matches_header"])
            self.assertEqual((3, 1), (bad["header_electric_count"], bad["extracted_rows"]))
            self.assertEqual(64, len(bad["source_sha256"]))

    def test_duplicates_in_the_table_are_reported_not_removed(self):
        with tempfile.TemporaryDirectory() as directory:
            result = extract(self.pdf(directory), HEADER + "\n".join([
                row("Extra Grande", "PORSCHE", "CAYENNE", "E"),
                row("Utilitário Esportivo Grande 4x4", "PORSCHE", "CAYENNE", "E"),
            ]))
        self.assertEqual(2, result["extracted_rows"])
        self.assertEqual(["PORSCHE|CAYENNE|E"], result["duplicate_keys"])
        self.assertEqual({"Extra Grande": 1, "Utilitário Esportivo Grande 4x4": 1}, result["categories_observed"])

    def test_nothing_is_admitted_or_classified(self):
        with tempfile.TemporaryDirectory() as directory:
            result = extract(self.pdf(directory), HEADER + row("Médio", "A", "B", "C"))
        self.assertFalse(result["usableForE8"])
        self.assertTrue(any("não prova circulação" in text for text in result["limitations"]))
        json.dumps(result)  # serialisable as written to disk


if __name__ == "__main__":
    unittest.main()
