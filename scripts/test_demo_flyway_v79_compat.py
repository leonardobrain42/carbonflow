"""Regression coverage for the exact historical V79 collision."""
import unittest
from unittest.mock import patch
import demo_flyway_v79_compat as compat

class V79CompatibilityTest(unittest.TestCase):
    def state(self, **changes):
        return dict({"history": [{"version": "78"}, {"version": "79"}],
                     "failed": False, "legacy_ok": True, "calendar_ok": False,
                     "timing_ok": False, "hourly_ok": False, "timing_table": False},
                    **changes)

    def test_original_and_published_checksums(self):
        compat.check_files()

    def test_only_exact_legacy_is_eligible(self):
        self.assertEqual("LEGACY", compat.state_kind(self.state()))
        self.assertEqual("REFUSED", compat.state_kind(self.state(legacy_ok=False)))
        self.assertEqual("REFUSED", compat.state_kind(self.state(failed=True)))
        self.assertEqual("REFUSED", compat.state_kind(self.state(
            history=[{"version": "79"}, {"version": "79"}])))

    def test_reclassified_and_upgraded_are_idempotent(self):
        pending = self.state(legacy_ok=False, calendar_ok=True,
                             history=[{"version": "78"}, {"version": "81"}])
        self.assertEqual("PENDING", compat.state_kind(pending))
        complete = dict(pending, timing_ok=True, hourly_ok=True, timing_table=True,
                        history=[{"version": v} for v in ("78","79","80","81")])
        self.assertEqual("COMPLETE", compat.state_kind(complete))
        self.assertEqual("REFUSED", compat.state_kind(dict(complete, timing_table=False)))
        self.assertEqual("REFUSED", compat.state_kind(dict(pending, hourly_ok=True)))

    def test_normal_main_does_not_need_metadata_changes(self):
        normal = self.state(legacy_ok=False, timing_ok=True, timing_table=True)
        self.assertEqual("NORMAL", compat.state_kind(normal))

    def test_reconcile_only_changes_history_under_lock(self):
        with patch.object(compat, "sql") as execute:
            compat.reconcile()
        statement = execute.call_args.args[0]
        self.assertIn(compat.GUARD, statement)
        self.assertIn("LOCK TABLE public.flyway_schema_history", statement)
        self.assertIn("SET version='81',script=", statement)
        self.assertNotIn("SET checksum", statement)
        self.assertNotIn("UPDATE public.app_open_e8_run", statement)

if __name__ == "__main__":
    unittest.main()
