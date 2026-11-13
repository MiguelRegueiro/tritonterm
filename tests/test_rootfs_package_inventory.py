import importlib.util
import io
import tarfile
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parents[1] / "scripts/generate-rootfs-package-inventory.py"
SPEC = importlib.util.spec_from_file_location("rootfs_package_inventory", SCRIPT)
INVENTORY = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(INVENTORY)


class RootfsPackageInventoryTests(unittest.TestCase):
    def test_parse_desc_keeps_multivalue_license_and_metadata(self):
        fields = INVENTORY.parse_desc(
            "%NAME%\nexample\n\n%VERSION%\n1.2-3\n\n%LICENSE%\nGPL-3.0-or-later\nMIT\n\n%URL%\nhttps://example.test\n"
        )
        self.assertEqual(fields["NAME"], ["example"])
        self.assertEqual(fields["LICENSE"], ["GPL-3.0-or-later", "MIT"])
        self.assertEqual(fields["URL"], ["https://example.test"])

    def test_inventory_reads_pacman_records_from_rootfs_tar(self):
        desc = (
            "%NAME%\nexample\n\n%VERSION%\n1.2-3\n\n%BASE%\nexample-base\n\n"
            "%LICENSE%\nGPL-3.0-or-later\nMIT\n\n%URL%\nhttps://example.test\n"
        ).encode()
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "rootfs.tar.gz"
            with tarfile.open(archive_path, "w:gz") as archive:
                entry = tarfile.TarInfo("./var/lib/pacman/local/example-1.2-3/desc")
                entry.size = len(desc)
                archive.addfile(entry, io.BytesIO(desc))
            rows = list(INVENTORY.inventory(archive_path))

        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["package"], "example")
        self.assertEqual(rows[0]["version"], "1.2-3")
        self.assertEqual(rows[0]["package_base"], "example-base")
        self.assertEqual(rows[0]["license_identifiers"], "GPL-3.0-or-later; MIT")
        self.assertEqual(rows[0]["upstream_url"], "https://example.test")


if __name__ == "__main__":
    unittest.main()
