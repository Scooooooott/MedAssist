import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch


SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR))

import scan_language


class ScanLanguageTests(unittest.TestCase):
    def test_doc_cjk_path_is_excluded(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "doc" / "\u4e2d\u6587.md"
            path.parent.mkdir()
            path.write_text("\u4e2d\u6587\n", encoding="utf-8")

            with patch.object(scan_language, "ROOT", root):
                self.assertTrue(scan_language.is_excluded(path))
                self.assertEqual(scan_language.scan_files(), [])

    def test_excluded_prefixes_match_complete_components(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(scan_language, "ROOT", root):
                self.assertTrue(scan_language.is_excluded(root / "docs" / "internal" / "note.md"))
                self.assertFalse(scan_language.is_excluded(root / "docs" / "internal-guide.md"))
                self.assertTrue(scan_language.is_excluded(root / "frontend" / "dist" / "bundle.js"))
                self.assertFalse(scan_language.is_excluded(root / "frontend" / "dist-copy" / "bundle.js"))

    def test_public_markdown_is_scanned(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            paths = [root / "public" / "README.md", root / "docs" / "guide.md"]
            for path in paths:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("\u4e2d\u6587\n", encoding="utf-8")

            with patch.object(scan_language, "ROOT", root):
                self.assertEqual(
                    sorted(scan_language.scan_files()),
                    sorted(
                        [
                            (str(Path("public") / "README.md"), 1, "\u4e2d\u6587"),
                            (str(Path("docs") / "guide.md"), 1, "\u4e2d\u6587"),
                        ]
                    ),
                )

    def test_clinical_data_is_not_excluded(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "services" / "clinical-data" / "README.md"
            path.parent.mkdir(parents=True)
            path.write_text("\u4e2d\u6587\n", encoding="utf-8")

            with patch.object(scan_language, "ROOT", root):
                self.assertFalse(scan_language.is_excluded(path))
                self.assertEqual(
                    scan_language.scan_files(),
                    [
                        (
                            str(Path("services") / "clinical-data" / "README.md"),
                            1,
                            "\u4e2d\u6587",
                        )
                    ],
                )


if __name__ == "__main__":
    unittest.main()
