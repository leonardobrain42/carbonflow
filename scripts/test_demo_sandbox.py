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

    def test_compose_uses_same_default_port_as_http_client(self):
        from unittest.mock import Mock
        with patch.object(demo, "PORT", "18088"), patch.dict(demo.os.environ, {}, clear=True), \
             patch.object(demo.subprocess, "run", return_value=Mock(stdout="")) as run:
            demo.compose("config", capture=True)
            call = run.call_args
            self.assertEqual(call.kwargs["env"]["CARBONFLOW_PORT"], "18088")
            self.assertEqual(call.args[0][-1], "config")


    def test_port_check_fails_closed_without_stopping_other_processes(self):
        rows = [{"Service": "db", "State": "running", "Health": "healthy"}]
        with patch.object(demo, "PORT", "18088"), \
             patch.object(demo, "local_port_busy", return_value=True):
            with self.assertRaisesRegex(RuntimeError, "porta 127.0.0.1:18088"):
                demo.check_port(rows)

    def test_owned_port_and_compose_json_formats(self):
        row = {"Service": "app", "State": "running", "Health": "healthy",
               "Publishers": [{"URL": "127.0.0.1", "TargetPort": 8080,
                               "PublishedPort": 18088, "Protocol": "tcp"}]}
        with patch.object(demo, "PORT", "18088"), \
             patch.object(demo, "local_port_busy", return_value=True), \
             patch.object(demo, "compose", return_value=__import__("json").dumps(row)):
            self.assertEqual(demo.service_rows(), [row])
            demo.check_port([row])
        with patch.object(demo, "compose", return_value=__import__("json").dumps([row])):
            self.assertEqual(demo.service_rows(), [row])

    def test_health_check_is_read_only_and_can_report_missing_backend(self):
        with patch.object(demo, "PORT", "18088"), \
             patch.object(demo, "runtime_check"), \
             patch.object(demo, "service_rows", return_value=[]), \
             patch.object(demo, "health_status", return_value=(None, "sem resposta")), \
             patch.object(demo, "local_port_busy", return_value=False), \
             patch.object(demo, "compose") as compose:
            self.assertFalse(demo.doctor())
            compose.assert_not_called()

    def test_doctor_requires_matching_demo_port_not_other_app(self):
        rows = [{"Service": "app", "State": "running", "Health": "healthy",
                 "Publishers": [{"URL": "127.0.0.1", "PublishedPort": 18088}]}]
        with patch.object(demo, "PORT", "18088"), \
             patch.object(demo, "runtime_check"), \
             patch.object(demo, "service_rows", return_value=rows), \
             patch.object(demo, "health_status", return_value=(200, "OK")), \
             patch.object(demo, "local_port_busy", return_value=True):
            self.assertTrue(demo.doctor())
            with patch.object(demo, "PORT", "18089"):
                self.assertFalse(demo.doctor())


    def test_host_readiness_retries_after_compose_health_without_changing_files(self):
        with patch.object(demo, "health_status",
                          side_effect=[(None, "sem resposta"), (503, "não pronto"), (200, "OK")]) as health, \
             patch.object(demo.time, "sleep") as sleep:
            demo.wait_for_health(attempts=3)
            self.assertEqual(health.call_count, 3)
            self.assertEqual(sleep.call_count, 2)

    def test_host_readiness_exhaustion_reports_port_and_diagnostic(self):
        with patch.object(demo, "PORT", "18088"), \
             patch.object(demo, "health_status", return_value=(None, "sem resposta")), \
             patch.object(demo.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "porta 18088"):
                demo.wait_for_health(attempts=3)
            self.assertEqual(sleep.call_count, 2)

    def test_compose_names_are_restricted(self):
        with patch.object(demo, "PROJECT", "prod/../../etc"), patch.object(demo, "PORT", "18088"):
            with self.assertRaises(ValueError):
                demo.validate()
        with patch.object(demo, "PROJECT", "carbonflow-demo"), patch.object(demo, "PORT", "18088"):
            demo.validate()


if __name__ == "__main__":
    unittest.main()
