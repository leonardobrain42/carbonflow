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

    def test_checksum_ignores_line_separators_as_flyway_does(self):
        with TemporaryDirectory() as dir:
            a=Path(dir)/"a.sql";b=Path(dir)/"b.sql"
            a.write_bytes(b"select 1;\nselect 2;\n")
            b.write_bytes(b"select 1;\r\nselect 2;")
            self.assertEqual(repair.checksum(a),repair.checksum(b))

if __name__=="__main__":
    unittest.main()
