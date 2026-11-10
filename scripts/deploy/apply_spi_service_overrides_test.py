import tempfile
import unittest
import zipfile
from pathlib import Path

from scripts.deploy.apply_spi_service_overrides import apply_overrides


class ApplyOverridesTest(unittest.TestCase):
    def test_replaces_providers_and_preserves_other_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "app.jar"
            rules = Path(directory) / "overrides.tsv"
            rules.write_text("META-INF/services/Format old.Provider new.Provider\n")
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr(
                    "META-INF/services/Format", "old.Provider\nnew.Provider\nother.Provider\n"
                )
                archive.writestr("payload.bin", b"unchanged")

            apply_overrides(jar, rules)

            with zipfile.ZipFile(jar) as archive:
                self.assertEqual(
                    archive.read("META-INF/services/Format"), b"new.Provider\nother.Provider\n"
                )
                self.assertEqual(archive.read("payload.bin"), b"unchanged")

    def test_missing_provider_leaves_original_archive_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "app.jar"
            rules = Path(directory) / "overrides.tsv"
            rules.write_text("META-INF/services/Format old.Provider new.Provider\n")
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/services/Format", "old.Provider\n")
            before = jar.read_bytes()

            with self.assertRaises(ValueError):
                apply_overrides(jar, rules)

            self.assertEqual(jar.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
