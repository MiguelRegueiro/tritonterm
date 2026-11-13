#!/usr/bin/env python3
"""Write the package/license inventory from a rootfs archive's local pacman DB."""

import argparse
import csv
import tarfile
from pathlib import Path


FIELDS = (
    "package",
    "version",
    "license_identifiers",
    "upstream_url",
    "package_base",
    "archlinuxarm_package_source_page",
)


def parse_desc(contents: str) -> dict[str, list[str]]:
    fields: dict[str, list[str]] = {}
    key = None
    for line in contents.splitlines():
        if line.startswith("%") and line.endswith("%"):
            key = line[1:-1]
            fields.setdefault(key, [])
        elif line and key:
            fields[key].append(line)
        elif not line:
            key = None
    return fields


def inventory(archive_path: Path):
    seen = set()
    with tarfile.open(archive_path, "r|gz") as archive:
        for member in archive:
            path = member.name.removeprefix("./")
            if not member.isfile() or not path.startswith("var/lib/pacman/local/") or not path.endswith("/desc"):
                continue
            stream = archive.extractfile(member)
            if stream is None:
                raise RuntimeError(f"cannot read pacman package record {member.name}")
            fields = parse_desc(stream.read().decode("utf-8", "replace"))
            name = fields.get("NAME", [""])[0]
            version = fields.get("VERSION", [""])[0]
            package_base = fields.get("BASE", [name])[0] or name
            if not name or not version:
                raise RuntimeError(f"package record missing name/version: {member.name}")
            if name in seen:
                raise RuntimeError(f"duplicate pacman package record for {name}")
            seen.add(name)
            licenses = "; ".join(fields.get("LICENSE", [])) or "unknown"
            url = fields.get("URL", [""])[0]
            yield {
                "package": name,
                "version": version,
                "license_identifiers": licenses,
                "upstream_url": url,
                "package_base": package_base,
                "archlinuxarm_package_source_page": (
                    f"https://archlinuxarm.org/packages/aarch64/{package_base}"
                ),
            }
    if not seen:
        raise RuntimeError("no pacman local database package records found in rootfs archive")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("rootfs_archive", type=Path)
    parser.add_argument("output_csv", type=Path)
    args = parser.parse_args()
    args.output_csv.parent.mkdir(parents=True, exist_ok=True)
    rows = sorted(inventory(args.rootfs_archive), key=lambda row: row["package"])
    with args.output_csv.open("w", newline="", encoding="utf-8") as output:
        writer = csv.DictWriter(output, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)
    print(f"package_inventory_records={len(rows)} output={args.output_csv}")


if __name__ == "__main__":
    main()
