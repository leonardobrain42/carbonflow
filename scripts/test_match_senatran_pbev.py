import json
import unittest

from match_senatran_pbev import compact, cross, import_payload, match, senatran_core


def row(brand, model, version, line=1, category="Médio"):
    return {"brand": brand, "model": model, "version": version, "category": category, "line": line}


ROWS = [
    row("BYD", "DOLPHIN", "GS 180EV", 1),
    row("BYD", "DOLPHIN MINI", "GS 5 EV", 2, "Sub Compacto"),
    row("BYD", "DOLPHIN MINI", "GL 5 EV", 3, "Sub Compacto"),
    row("Renault", "E-KWID", "INTENS", 4),
    row("Renault", "E-KWID", "CARGO", 5, "Comercial"),
    row("PORSCHE", "CAYENNE", "E", 6, "Extra Grande"),
    row("PORSCHE", "CAYENNE", "E", 7, "Utilitário Esportivo Grande 4x4"),
    row("AUDI", "A6", "e-tron", 8),
    row("AUDI", "A6", "e-tron S line", 9),
]


class MatchTest(unittest.TestCase):
    def test_compaction_drops_accents_case_spaces_and_the_import_marker_only(self):
        self.assertEqual("BYDDOLPHINMINIGS5EV", compact("BYD Dolphin Mini GS 5 EV"))
        self.assertEqual("ELETRICO", compact("Elétrico"))
        self.assertEqual("BYDDOLPHINMINIGS5EV", senatran_core("I/BYD DOLPHIN MINI GS5EV"))
        self.assertEqual("BYDDOLPHINMINIGS5EV", senatran_core("BYD/DOLPHIN MINI GS5EV"))

    def test_exact_match_ignores_spacing_case_and_the_import_marker(self):
        for literal in ("I/BYD DOLPHIN MINI GS5EV", "BYD/DOLPHIN MINI GS5EV"):
            result = match(literal, ROWS)
            self.assertEqual("EXACT", result["status"], literal)
            self.assertEqual([2], [c["pbev_line"] for c in result["candidates"]])
        self.assertEqual("EXACT", match("RENAULT/E-KWID INTENS", ROWS)["status"])  # brand case differs in the table

    def test_unlisted_version_is_model_only_and_lists_every_version_undecided(self):
        result = match("I/RENAULT E-KWID TECHNO", ROWS)
        self.assertEqual("MODEL_ONLY", result["status"])
        self.assertEqual({"INTENS", "CARGO"}, {c["version"] for c in result["candidates"]})
        self.assertIn("indecidida", result["reason"])

    def test_the_longest_model_wins_so_a_shorter_model_does_not_add_noise(self):
        result = match("I/BYD DOLPHIN MINI GS9EV", ROWS)  # no such version
        self.assertEqual({"DOLPHIN MINI"}, {c["model"] for c in result["candidates"]})

    def test_no_match_is_a_result_not_a_guess(self):
        for literal in ("I/NISSAN LEAF TEKNA", "RENAULT/KWID EVOLUTION", "AGRALE/MAXIBUS DOLPHIN"):
            result = match(literal, ROWS)
            self.assertEqual("NONE", result["status"], literal)
            self.assertEqual([], result["candidates"])

    def test_one_model_with_several_versions_is_model_only_with_all_versions_listed(self):
        self.assertEqual("EXACT", match("AUDI A6 E-TRON", ROWS)["status"])  # 'e-tron' only; 'S line' is another key
        result = match("I/AUDI A6", ROWS)
        self.assertEqual("MODEL_ONLY", result["status"])
        self.assertEqual({"e-tron", "e-tron S line"}, {c["version"] for c in result["candidates"]})

    def test_distinct_keys_that_compact_to_the_same_text_are_ambiguous_and_never_collapsed(self):
        rows = [row("X", "A B", "C", 1), row("X", "A", "B C", 2)]
        result = match("X A B C", rows)
        self.assertEqual("AMBIGUOUS", result["status"])
        self.assertEqual([1, 2], [c["pbev_line"] for c in result["candidates"]])
        models = [row("X", "ABC", "V1", 3), row("X", "ABCD", "V2", 4)]
        self.assertEqual("MODEL_ONLY", match("X ABCD Z", models)["status"])  # the longest model alone

    def test_same_key_in_two_categories_is_one_match_with_both_lines(self):
        result = match("PORSCHE/CAYENNE E", ROWS)
        self.assertEqual("EXACT", result["status"])
        self.assertEqual([6, 7], [c["pbev_line"] for c in result["candidates"]])

    def test_nothing_is_ever_approved_or_admitted(self):
        report = cross(["I/BYD DOLPHIN MINI GS5EV", "X"], {"rows": ROWS, "source_sha256": "a" * 64,
                                                            "count_matches_header": True})
        self.assertEqual([], report["approved_aliases"])
        self.assertFalse(report["usableForE8"])
        self.assertEqual({"EXACT": 1, "NONE": 1}, report["status_counts"])
        for item in report["matches"]:
            self.assertTrue(item["requires_review"])
            self.assertFalse(item["approved"])

    def test_import_payload_skips_unmatched_names_and_uses_the_csv_bridge_key(self):
        pbev = {"rows": ROWS, "source_sha256": "b" * 64, "count_matches_header": True}
        report = cross(["I/BYD DOLPHIN MINI GS5EV", "I/NISSAN LEAF TEKNA", "I/RENAULT E-KWID TECHNO"], pbev)
        payload = import_payload(report, "2026-09")
        self.assertEqual("2026-09", payload["observationPeriod"])
        self.assertEqual("b" * 64, payload["pbevSourceSha256"])
        self.assertEqual(["I/BYD DOLPHIN MINI GS5EV", "I/RENAULT E-KWID TECHNO"], [i["literalName"] for i in payload["items"]])
        self.assertEqual("I_BYD_DOLPHIN_MINI_GS5EV", payload["items"][0]["recordKey"])  # same key as build_evr_stock_csv
        self.assertEqual({"EXACT", "MODEL_ONLY"}, {i["level"] for i in payload["items"]})
        self.assertEqual({"brand", "model", "version", "category", "pbevLine"}, set(payload["items"][0]["matches"][0]))
        self.assertNotIn("approved", json.dumps(payload))


if __name__ == "__main__":
    unittest.main()
