#!/usr/bin/env python3
"""Unit checks for TOTP compatibility and local-only credential handling."""
from pathlib import Path
import base64
import importlib.util
import hmac
import hashlib
import os
import secrets
import subprocess
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent))
import admin_sandbox
import demo_sandbox


class AdminSandboxTest(unittest.TestCase):
    def test_totp_agrees_with_rfc6238_hmac_sha1(self):
        # RFC6238 test seed and 30s steps, six-digit truncation.
        secret=b"12345678901234567890"
        self.assertEqual(admin_sandbox.totp(secret,59 // 30),"287082")
        self.assertEqual(len(admin_sandbox.totp(secret,1234567890 // 30)),6)

    def test_private_config_rejects_world_readable(self):
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"config.json"
            config={"identity":str(__import__('uuid').uuid4()),
                "organization":str(__import__('uuid').uuid4()),
                "secret":base64.urlsafe_b64encode(secrets.token_bytes(20)).decode().rstrip("=")}
            file.write_text(__import__('json').dumps(config))
            file.chmod(0o644)
            with patch.object(admin_sandbox,"CONFIG",file):
                with self.assertRaises(ValueError):
                    admin_sandbox.private_config()
            file.chmod(0o600)
            with patch.object(admin_sandbox,"CONFIG",file):
                self.assertEqual(admin_sandbox.private_config(),config)

    def test_compose_only_injects_staff_from_private_file(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory)
            folder.chmod(0o700)
            import json,uuid
            data={"identity":str(uuid.uuid4()),"organization":str(uuid.uuid4()),
                  "secret":base64.urlsafe_b64encode(secrets.token_bytes(20)).decode().rstrip("=")}
            (folder/"admin-roster.json").write_text(json.dumps(data))
            (folder/"admin-roster.json").chmod(0o600)
            captured={}
            def fake_run(command,**kwargs):
                captured.update(kwargs["env"])
                return type("Proc",(),{"stdout":""})()
            with patch.object(demo_sandbox,"SECRET_DIR",folder),patch.object(
                demo_sandbox.subprocess,"run",side_effect=fake_run):
                demo_sandbox.compose("ps")
            self.assertEqual(captured["CARBONFLOW_PLATFORM_SOURCE_READINESS_VIEWERS"],data["identity"])
            self.assertEqual(captured["CARBONFLOW_PLATFORM_SOURCE_WORK_MANAGERS"],data["identity"])
            self.assertEqual(captured["CARBONFLOW_PLATFORM_SOURCE_INGESTION_REVIEWERS"],data["identity"])
            self.assertEqual(captured["CARBONFLOW_PLATFORM_SOURCE_TECHNICAL_REVIEWERS"],data["identity"])
            for owner_role in ("CONTENT_LICENSES_OWNERS","FUEL_CATALOG_MANAGERS","FUEL_CATALOG_OWNERS","GRID_SERIES_MANAGERS","GRID_SERIES_OWNERS"):
                self.assertEqual(captured["CARBONFLOW_PLATFORM_"+owner_role],data["identity"])
            # Never an independent role: dual custody is not simulated.
            for independent in ("CONTENT_LICENSES_APPROVERS","CONTENT_LICENSES_SUBMITTERS","FUEL_CATALOG_REVIEWERS","GRID_SERIES_REVIEWERS"):
                self.assertNotIn("CARBONFLOW_PLATFORM_"+independent,captured)
            self.assertEqual(captured["CARBONFLOW_COMPLIANCE_STAFF"],
                             data["identity"]+":"+data["secret"]+":"+data["organization"])

    def roster(self,folder,name):
        import json,uuid
        data={"identity":str(uuid.uuid4()),"organization":str(uuid.uuid4()),
              "secret":base64.urlsafe_b64encode(secrets.token_bytes(20)).decode().rstrip("=")}
        (folder/name).write_text(json.dumps(data))
        (folder/name).chmod(0o600)
        return data

    def captured_env(self,folder):
        captured={}
        def fake_run(command,**kwargs):
            captured.update(kwargs["env"])
            return type("Proc",(),{"stdout":""})()
        with patch.object(demo_sandbox,"SECRET_DIR",folder),patch.object(demo_sandbox.subprocess,"run",side_effect=fake_run):
            demo_sandbox.compose("ps")
        return captured

    def test_owner_can_import_and_second_account_is_only_the_independent_side(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory); folder.chmod(0o700)
            owner=self.roster(folder,"admin-roster.json")
            solo=self.captured_env(folder)
            # The owner account can import and decide on the owner basis in the alias queue (it had no role there before).
            self.assertEqual(solo["CARBONFLOW_PLATFORM_FLEET_ALIAS_CANDIDATES_IMPORTERS"],owner["identity"])
            self.assertEqual(solo["CARBONFLOW_PLATFORM_FLEET_ALIAS_CANDIDATES_OWNERS"],owner["identity"])
            for independent in ("FLEET_ALIAS_CANDIDATES_REVIEWERS","CONTENT_LICENSES_APPROVERS"):
                self.assertNotIn("CARBONFLOW_PLATFORM_"+independent,solo)
            approver=self.roster(folder,"admin2-roster.json")
            both=self.captured_env(folder)
            self.assertEqual(both["CARBONFLOW_COMPLIANCE_STAFF"],";".join(
                r["identity"]+":"+r["secret"]+":"+r["organization"] for r in (owner,approver)))
            for role in ("CONTENT_LICENSES_APPROVERS","FUEL_CATALOG_REVIEWERS","GRID_SERIES_REVIEWERS",
                         "FLEET_COVERAGE_REVIEWERS","FLEET_ALIAS_CANDIDATES_REVIEWERS"):
                self.assertEqual(both["CARBONFLOW_PLATFORM_"+role],approver["identity"])
            # Dual custody: the approver never holds an owner/importer/submitter role, and the owner never an independent one.
            owner_roles=("CONTENT_LICENSES_OWNERS","FUEL_CATALOG_OWNERS","GRID_SERIES_OWNERS","FLEET_ALIAS_CANDIDATES_OWNERS",
                         "FLEET_ALIAS_CANDIDATES_IMPORTERS","SOURCE_WORK_MANAGERS")
            for role in owner_roles:
                self.assertEqual(both["CARBONFLOW_PLATFORM_"+role],owner["identity"])
            for role in ("CONTENT_LICENSES_APPROVERS","FLEET_ALIAS_CANDIDATES_REVIEWERS"):
                self.assertNotIn(owner["identity"],both["CARBONFLOW_PLATFORM_"+role])
            self.assertEqual(both["CARBONFLOW_PLATFORM_SOURCE_READINESS_VIEWERS"],owner["identity"]+","+approver["identity"])

    def test_approver_setup_requires_the_owner_account_first_and_has_its_own_private_file(self):
        with tempfile.TemporaryDirectory() as directory:
            folder=Path(directory); folder.chmod(0o700)
            with patch.object(admin_sandbox,"CONFIG",folder/"admin-roster.json"),patch.object(
                    admin_sandbox,"APPROVER_CONFIG",folder/"admin2-roster.json"),patch.object(admin_sandbox.demo,"validate"):
                with self.assertRaisesRegex(ValueError,"Crie primeiro"):
                    admin_sandbox.setup(admin_sandbox.APPROVER_EMAIL,admin_sandbox.APPROVER_CONFIG,approver=True)
        self.assertNotEqual(admin_sandbox.ADMIN_EMAIL,admin_sandbox.APPROVER_EMAIL)
        self.assertNotEqual(admin_sandbox.CONFIG.name,admin_sandbox.APPROVER_CONFIG.name)

if __name__=="__main__":
    unittest.main()
