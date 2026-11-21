"""Guardrails for local V71 compatibility: refuse unrelated schemas."""
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
import demo_flyway_compat as repair

class TestDemoFlywayCompatibility(unittest.TestCase):
    def test_unknown_checksum_is_refused(self):
        state={"history":[{"version":"69","checksum":repair.V69,"success":True},
                          {"version":"70","checksum":123,"success":True},
                          {"version":"71","checksum":456,"success":True}]}
        self.assertTrue(any("legado" in x for x in repair.problems(state)))

    def test_different_schema_cannot_be_reconciled(self):
        state={"history":[{"version":"69","checksum":repair.V69,"success":True},
                          {"version":"70","checksum":123,"success":True},
                          {"version":"71","checksum":repair.OLD,"success":True}],
               "run":{}, "command":{}, "constraints":"","command_constraints":"",
               "triggers":{}, "indexes":[], "actions":""}
        issues=repair.problems(state)
        self.assertTrue(any("colunas" in x for x in issues))
        self.assertTrue(any("trigger" in x for x in issues))

    def test_legacy_filename_v71_is_recognized_only_with_complete_proof(self):
        valid = {
            "history": [
                {"version":"69","checksum":repair.V69,"success":True},
                {"version":"70","checksum":123,"success":True},
                {"version":"71","checksum":repair.OLD,"success":True,
                 "script":repair.LEGACY_FILENAME_SCRIPT,
                 "description":repair.LEGACY_FILENAME_DESCRIPTION},
            ],
            "run":{}, "command":{},
            "filename":{"udt_name":"text","nullable":"NO"},
            "filename_trigger":"O",
            "filename_constraint": (
                "CHECK (((submitted_file_name ~ "
                "'^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf
        with TemporaryDirectory() as dir:
            a=Path(dir)/"a.sql";b=Path(dir)/"b.sql"
            a.write_bytes(b"select 1;\nselect 2;\n")
            b.write_bytes(b"select 1;\r\nselect 2;")
            self.assertEqual(repair.checksum(a),repair.checksum(b))

if __name__=="__main__":
    unittest.main()
::text) "
                "AND (submitted_file_name !~~ '%..%'::text)))"
            )
        }
        self.assertEqual([],repair.legacy_filename_problems(valid))
        invalid = dict(valid, filename={"udt_name":"text","nullable":"YES"})
        self.assertTrue(any("NOT NULL" in msg for msg in
                            repair.legacy_filename_problems(invalid)))

    def test_legacy_reclassification_rejects_checksum_and_script_mismatch(self):
        base = {
            "history": [
                {"version":"69","checksum":repair.V69,"success":True},
                {"version":"70","checksum":123,"success":True},
                {"version":"71","checksum":repair.OLD,"success":True,
                 "script":repair.LEGACY_FILENAME_SCRIPT,
                 "description":repair.LEGACY_FILENAME_DESCRIPTION},
            ],
            "run":{}, "command":{}, "filename":{"udt_name":"text","nullable":"NO"},
            "filename_trigger":"O",
            "filename_constraint":"submitted_file_name ~ A-Za-z0-9 pdf %..%"
        }
        collision = dict(base, history=base["history"]+[{"version":"69.1","checksum":99,"success":True}])
        self.assertTrue(any("já existe" in msg for msg in
                            repair.legacy_filename_problems(collision)))
        wrong_script = dict(base, history=base["history"][:-1]+[
            dict(base["history"][-1], script="db/migration/V71__other.sql")])
        self.assertTrue(any("não corresponde" in msg for msg in
                            repair.legacy_filename_problems(wrong_script)))
        with_e8 = dict(base, run={"id":"uuid"})
        self.assertTrue(any("E8 criou tabelas" in msg for msg in
                            repair.legacy_filename_problems(with_e8)))

    def test_checksum_ignores_line_separators_as_flyway_does(self):
        with TemporaryDirectory() as dir:
            a=Path(dir)/"a.sql";b=Path(dir)/"b.sql"
            a.write_bytes(b"select 1;\nselect 2;\n")
            b.write_bytes(b"select 1;\r\nselect 2;")
            self.assertEqual(repair.checksum(a),repair.checksum(b))

if __name__=="__main__":
    unittest.main()
