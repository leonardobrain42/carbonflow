"""Regression guard: applied V69 is immutable; filename is added in V69.1."""
from pathlib import Path
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

def flyway_crc(path: Path) -> int:
    # Flyway calculates CRC32 over UTF-8 lines excluding their line separators.
    sql = path.read_text(encoding="utf-8")
    value = zlib.crc32("".join(sql.splitlines()).encode("utf-8"))
    return value if value < 2**31 else value - 2**32

class FlywayV69CompatibilityTest(unittest.TestCase):
    def test_applied_v69_checksum_remains_exact(self):
        self.assertEqual(flyway_crc(MIGRATIONS / "V69__open_asset_evidence_links.sql"),
                         -1778044537)

    def test_filename_is_added_by_additive_69_1(self):
        old = (MIGRATIONS / "V69__open_asset_evidence_links.sql").read_text(encoding="utf-8")
        new = (MIGRATIONS / "V69_1__open_asset_evidence_submitted_filename.sql").read_text(encoding="utf-8")
        self.assertNotIn("submitted_file_name", old)
        self.assertIn("ADD COLUMN submitted_file_name text", new)
        self.assertIn("blob.id = link.evidence_blob_id", new)
        self.assertIn("blob.project_id = link.project_id", new)
        self.assertIn("blob.mime_type = 'application/pdf'", new)
        self.assertIn("ALTER COLUMN submitted_file_name SET NOT NULL", new)
        self.assertIn("app_open_asset_evidence_submitted_file_name_check", new)

if __name__ == "__main__":
    unittest.main()
