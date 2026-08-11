import json
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from console.adapters import ActionRejected, JavaGovernanceApiAdapter
from scripts.check_migrations import check_migrations, check_models


ROOT = Path(__file__).resolve().parents[1]


class BoundaryTests(unittest.TestCase):
    def test_all_business_models_are_unmanaged(self):
        self.assertEqual(check_models(ROOT / "console" / "models.py"), [])

    def test_business_migration_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            migration = Path(directory) / "console" / "migrations"
            migration.mkdir(parents=True)
            (migration / "0001_initial.py").write_text(
                "operations = [migrations.CreateModel(name='Quarantine')]", encoding="utf-8"
            )
            self.assertEqual(len(check_migrations(Path(directory))), 1)

    def test_managed_true_model_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            models = Path(directory) / "models.py"
            models.write_text(
                "class Example(models.Model):\n"
                "    class Meta:\n"
                "        managed = True\n",
                encoding="utf-8",
            )
            self.assertEqual(len(check_models(models)), 1)

    def test_state_changes_default_to_deny(self):
        with self.assertRaises(ActionRejected):
            JavaGovernanceApiAdapter().change_state("feedback", uuid4(), "approve", "demo-admin")

    def test_realm_has_roles_users_and_no_credentials(self):
        realm = json.loads((ROOT.parent / "deploy" / "keycloak" / "realm-medassist.json").read_text())
        self.assertEqual({role["name"] for role in realm["roles"]["realm"]}, {"CLINICIAN", "RESEARCHER", "ADMIN"})
        self.assertEqual(len(realm["users"]), 3)
        self.assertTrue(any(client["publicClient"] for client in realm["clients"]))
        self.assertTrue(any(not client["publicClient"] for client in realm["clients"]))
        self.assertFalse(any("password" in user or "credentials" in user for user in realm["users"]))


if __name__ == "__main__":
    unittest.main()
