"""Fast offline checks for opt-in demonstration credential handling."""
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch

import demo_sandbox as demo


class DemoSandboxTest(unittest.TestCase):
    def test_email_rejects_real_domains(self):
        self.assertEqual(demo.normalize_email(" Demo@Carbonflow.Test "), "demo@carbonflow.test")
        for address in ("person@gmail.com", "admin@example.org", "bad@carbonflow.test.evil", "../../etc/passwd", "a@b..test"):
            with self.subTest(address=address):
                with self.assertRaises(ValueError):
                    demo.normalize_email(address)

    def test_credentials_are_random_private_and_stable(self):
        with tempfile.TemporaryDirectory() as directory:
            location = Path(directory) / ".carbonflow-demo"
            with patch.object(demo, "SECRET_DIR", location), patch.object(demo, "ACCOUNT_DIR", location / "accounts"):
                first = demo.credentials("demo@carbonflow.test", "Demonstração", True)
                second = demo.credentials("demo@carbonflow.test", "Outra identidade", True)
                other = demo.credentials("demo2@carbonflow.test", "Demonstração", True)
                self.assertEqual(first, second)
                self.assertNotEqual(first["password"], other["password"])
                self.assertGreaterEqual(len(first["password"]), 24)
                self.assertEqual(stat.S_IMODE(location.stat().st_mode), 0o700)
                self.assertEqual(stat.S_IMODE((location / "accounts").stat().st_mode), 0o700)
                self.assertEqual(stat.S_IMODE(demo.credential_file(first["email"]).stat().st_mode), 0o600)
                self.assertEqual(first, demo.credentials("demo@carbonflow.test", "Demonstração", False))

    def test_existing_insecure_credentials_are_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            location = Path(directory) / ".carbonflow-demo"
            with patch.object(demo, "SECRET_DIR", location), patch.object(demo, "ACCOUNT_DIR", location / "accounts"):
                saved = demo.credentials("demo@carbonflow.test", "Teste", True)
                file = demo.credential_file(saved["email"])
                file.chmod(0o644)
                with self.assertRaisesRegex(ValueError, "não é privado"):
                    demo.credentials(saved["email"], "Teste", True)

    def test_compose_names_are_restricted(self):
        with patch.object(demo, "PROJECT", "prod/../../etc"), patch.object(demo, "PORT", "18088"):
            with self.assertRaises(ValueError):
                demo.validate()
        with patch.object(demo, "PROJECT", "carbonflow-demo"), patch.object(demo, "PORT", "18088"):
            demo.validate()


if __name__ == "__main__":
    unittest.main()
