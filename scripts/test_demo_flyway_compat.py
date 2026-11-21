"""Guardrails for the two distinct historical V71 states in a demo DB."""
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
import demo_flyway_compat as repair


class TestDemoFlywayCompatibility(unittest.TestCase):
    def legacy_state(self):
        pdf_check = (
            "CHECK (((submitted_file_name ~ "
            "'^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf"
            + chr(36) +
            "'::text) AND (submitted_file_name !~~ '%..%'::text)))"
        )
        return {
            "history": [
                {"version": "69", "checksum": repair.V69, "success": True},
                {"version": "70", "checksum": 123, "success": True},
                {"version": "71", "checksum": repair.OLD, "success": True,
                 "script": repair.LEGACY_FILENAME_SCRIPT,
                 "description": repair.LEGACY_FILENAME_DESCRIPTION},
            ],
            "run": {},
            "command": {},
            "filename": {"udt_name": "text", "nullable": "NO"},
            "filename_trigger": "O",
            "filename_constraint": pdf_check,
        }

    def test_unknown_checksum_is_refused(self):
        state = {
            "history": [
                {"version": "69", "checksum": repair.V69, "success": True},
                {"version": "70", "checksum": 123, "success": True},
                {"version": "71", "checksum": 456, "success": True},
            ]
        }
        self.assertTrue(any("legado" in x for x in repair.problems(state)))

    def test_e8_reconciliation_rejects_unrelated_schema(self):
        state = {
            "history": [
                {"version": "69", "checksum": repair.V69, "success": True},
                {"version": "70", "checksum": 123, "success": True},
                {"version": "71", "checksum": repair.OLD, "success": True},
            ],
            "run": {}, "command": {},
            "constraints": "", "command_constraints": "",
            "triggers": {}, "indexes": [], "actions": ""
        }
        issues = repair.problems(state)
        self.assertTrue(any("colunas" in x for x in issues))
        self.assertTrue(any("trigger" in x for x in issues))

    def test_filename_collision_requires_correct_schema(self):
        valid = self.legacy_state()
        self.assertEqual([], repair.legacy_filename_problems(valid))
        incorrect = dict(valid, filename={"udt_name": "text", "nullable": "YES"})
        self.assertTrue(any("NOT NULL" in msg for msg in
                            repair.legacy_filename_problems(incorrect)))

    def test_filename_collision_rejects_wrong_history_and_e8_tables(self):
        valid = self.legacy_state()
        collision = dict(valid, history=valid["history"] +
                         [{"version": "69.1", "checksum": 99, "success": True}])
        self.assertTrue(any("já existe" in msg for msg in
                            repair.legacy_filename_problems(collision)))
        wrong = dict(valid, history=valid["history"][:-1] + [
            dict(valid["history"][-1], script="db/migration/V71__other.sql")])
        self.assertTrue(any("não corresponde" in msg for msg in
                            repair.legacy_filename_problems(wrong)))
        with_e8 = dict(valid, run={"id": "uuid"})
        self.assertTrue(any("E8 criou tabelas" in msg for msg in
                            repair.legacy_filename_problems(with_e8)))

    def test_flyway_crc_ignores_line_separators(self):
        with TemporaryDirectory() as directory:
            a = Path(directory) / "a.sql"
            b = Path(directory) / "b.sql"
            a.write_bytes(b"select 1;\nselect 2;\n")
            b.write_bytes(b"select 1;\r\nselect 2;")
            self.assertEqual(repair.checksum(a), repair.checksum(b))


if __name__ == "__main__":
    unittest.main()
