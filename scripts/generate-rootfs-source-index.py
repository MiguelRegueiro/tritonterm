#!/usr/bin/env python3
"""Resolve exact public PKGBUILD commits for a signed TritonTerm rootfs inventory."""

import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from datetime import datetime


USER_AGENT = "TritonTerm-Release-Source-Index/1.0"
ALARM_TREE_URL = "https://api.github.com/repos/archlinuxarm/PKGBUILDs/git/trees/master?recursive=1"
ALARM_COMMITS = "https://api.github.com/repos/archlinuxarm/PKGBUILDs/commits"
ARCH_COMMITS = "https://gitlab.archlinux.org/api/v4/projects/archlinux%2Fpackaging%2Fpackages%2F{}/repository/commits"
ALARM_RAW = "https://raw.githubusercontent.com/archlinuxarm/PKGBUILDs/{}/{}"
ARCH_RAW = "https://gitlab.archlinux.org/archlinux/packaging/packages/{}/-/raw/{}/PKGBUILD"
ARCH_SRCINFO = "https://gitlab.archlinux.org/archlinux/packaging/packages/{}/-/raw/{}/.SRCINFO"
META_BASES = {"base", "base-devel", "licenses"}


def request_json(url):
    for attempt in range(6):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return json.load(response)
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt == 5:
                raise RuntimeError(f"request failed: {url}: {exc}") from exc
            time.sleep(1 + attempt)


def request_text(url):
    for attempt in range(6):
        req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(req, timeout=30) as response:
                return response.read().decode("utf-8", "replace")
        except (urllib.error.URLError, TimeoutError) as exc:
            if attempt == 5:
                raise RuntimeError(f"request failed: {url}: {exc}") from exc
            time.sleep(1 + attempt)


def get_version(text):
    version = text.split(":", 1)[-1]
    return version


def recipe_version_matches(text, package_version):
    match = re.search(r"(?m)^pkgver=(?:'([^']+)'|\"([^\"]+)\"|([^\s#]+))\s*$", text)
    rel = re.search(r"(?m)^pkgrel=(?:'([^']+)'|\"([^\"]+)\"|([^\s#]+))\s*$", text)
    if not match or not rel:
        return False
    pkgver = next(value for value in match.groups() if value is not None)
    pkgrel = next(value for value in rel.groups() if value is not None)
    return f"{pkgver}-{pkgrel}" == get_version(package_version)


def srcinfo_version_matches(text, package_version):
    pkgver = re.search(r"(?m)^\s*pkgver = (.+?)\s*$", text)
    pkgrel = re.search(r"(?m)^\s*pkgrel = (.+?)\s*$", text)
    if not pkgver or not pkgrel:
        return False
    return f"{pkgver.group(1)}-{pkgrel.group(1)}" == get_version(package_version)


def find_exact(repo_kind, path, package_version, pkgbase):
    if repo_kind == "archlinuxarm":
        url = ALARM_COMMITS + "?" + urllib.parse.urlencode({"path": path, "per_page": 100})
        commits = request_json(url)
        get_commit = lambda item: item["sha"]
        get_title = lambda item: item["commit"]["message"].splitlines()[0]
        get_date = lambda item: item["commit"]["author"]["date"]
        raw_url = lambda sha: ALARM_RAW.format(sha, path)
        page_url = lambda sha: f"https://github.com/archlinuxarm/PKGBUILDs/blob/{sha}/{path}"
    else:
        api = ARCH_COMMITS.format(urllib.parse.quote(pkgbase, safe=""))
        url = api + "?" + urllib.parse.urlencode({"path": "PKGBUILD", "per_page": 100})
        commits = request_json(url)
        get_commit = lambda item: item["id"]
        get_title = lambda item: item["title"].splitlines()[0]
        get_date = lambda item: item["committed_date"]
        raw_url = lambda sha: ARCH_RAW.format(pkgbase, sha)
        page_url = lambda sha: f"https://gitlab.archlinux.org/archlinux/packaging/packages/{pkgbase}/-/blob/{sha}/PKGBUILD"

    target = get_version(package_version)
    candidates = [item for item in commits if target in get_title(item) and get_date(item) <= BUILD_DATE]
    for item in candidates:
        sha = get_commit(item)
        recipe = request_text(raw_url(sha))
        if recipe_version_matches(recipe, package_version):
            return sha, get_title(item), page_url(sha), recipe
        if repo_kind == "archlinux":
            srcinfo_url = ARCH_SRCINFO.format(pkgbase, sha)
            try:
                srcinfo = request_text(srcinfo_url)
            except RuntimeError:
                srcinfo = ""
            if srcinfo_version_matches(srcinfo, package_version):
                return sha, get_title(item), page_url(sha), recipe
    # Some upstream commit messages omit the package version. In that case,
    # inspect recipes at the build date and accept only one declaring the exact
    # pkgver/pkgrel from the installed local package database.
    for item in commits:
        if get_date(item) > BUILD_DATE:
            continue
        sha = get_commit(item)
        recipe = request_text(raw_url(sha))
        if recipe_version_matches(recipe, package_version):
            return sha, get_title(item), page_url(sha), recipe
    return None


def main():
    if len(sys.argv) != 4:
        raise SystemExit("Usage: generate-rootfs-source-index.py ROOTFS_PACKAGES_CSV ALARM_TREE_JSON OUTPUT_DIR")
    build_date = os.environ.get("TRITONTERM_ROOTFS_BUILD_DATE")
    if not build_date:
        raise SystemExit(
            "Set TRITONTERM_ROOTFS_BUILD_DATE to the signed rootfs build report's started_utc value."
        )
    try:
        datetime.fromisoformat(build_date.replace("Z", "+00:00"))
    except ValueError as exc:
        raise SystemExit("TRITONTERM_ROOTFS_BUILD_DATE must be an ISO-8601 timestamp.") from exc
    global BUILD_DATE
    BUILD_DATE = build_date
    csv_path, tree_path, output_dir = map(Path, sys.argv[1:])
    output_dir.mkdir(parents=True, exist_ok=True)
    rows = list(csv.DictReader(csv_path.open(newline="")))
    tree = json.load(tree_path.open())["tree"]
    alarm_paths = [item["path"] for item in tree if item["path"].endswith("/PKGBUILD")]
    alarm_by_base = {}
    for path in alarm_paths:
        base = path.split("/")[-2]
        alarm_by_base.setdefault(base, []).append(path)

    bases = {}
    for row in rows:
        bases.setdefault(row["package_base"], []).append(row)

    found = {}
    failures = []
    for index, (pkgbase, package_rows) in enumerate(sorted(bases.items()), 1):
        if pkgbase in META_BASES:
            found[pkgbase] = {
                "source_type": "meta-package",
                "commit": "",
                "recipe_url": "",
                "recipe_path": "",
                "recipe_file": "",
                "status": "no executable source; package contains metadata only",
            }
            continue
        package_version = package_rows[0]["version"]
        if pkgbase == "paru":
            recipe_file = "app/src/main/assets/paru-bootstrap.PKGBUILD"
            found[pkgbase] = {
                "source_type": "AUR recipe bundled with TritonTerm source",
                "commit": "AUR snapshot and upstream source are checksum-pinned in the bundled PKGBUILD",
                "recipe_url": "https://aur.archlinux.org/packages/paru",
                "recipe_path": recipe_file,
                "recipe_file": recipe_file,
                "status": "exact 2.2.0 PKGBUILD and source checksum included in Android source archive",
            }
            continue

        source = None
        alarm_candidates = alarm_by_base.get(pkgbase, [])
        for path in alarm_candidates:
            source = find_exact("archlinuxarm", path, package_version, pkgbase)
            if source:
                break
        source_type = "Arch Linux ARM PKGBUILDs" if source else "Arch Linux packaging GitLab"
        if source is None:
            source = find_exact("archlinux", f"{pkgbase}/PKGBUILD", package_version, pkgbase)
        if source is None:
            failures.append(f"{pkgbase} {package_version}: no exact recipe version found")
            continue
        sha, title, recipe_url, recipe_text = source
        safe_name = re.sub(r"[^A-Za-z0-9._+-]", "_", pkgbase)
        recipe_file = f"package-recipes/{safe_name}/PKGBUILD"
        dest = output_dir / recipe_file
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(recipe_text)
        found[pkgbase] = {
            "source_type": source_type,
            "commit": sha,
            "recipe_url": recipe_url,
            "recipe_path": recipe_file,
            "recipe_file": recipe_file,
            "status": f"exact package version verified in pinned PKGBUILD ({title})",
        }
        print(f"[{index}/{len(bases)}] {pkgbase} {package_version}: {source_type} {sha}", flush=True)

    output_rows = []
    for row in rows:
        resolved = found.get(row["package_base"], {})
        output_rows.append({
            **row,
            "source_type": resolved.get("source_type", "UNRESOLVED"),
            "source_commit": resolved.get("commit", ""),
            "source_recipe_url": resolved.get("recipe_url", ""),
            "bundled_recipe_path": resolved.get("recipe_path", ""),
            "source_status": resolved.get("status", "UNRESOLVED"),
        })
    columns = list(output_rows[0])
    with (output_dir / "package-source-index.csv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(output_rows)
    if failures:
        (output_dir / "source-index-failures.txt").write_text("\n".join(failures) + "\n")
        print(f"Unresolved source recipes: {len(failures)}", file=sys.stderr)
        for item in failures:
            print(item, file=sys.stderr)
        raise SystemExit(1)
    (output_dir / "source-index-failures.txt").unlink(missing_ok=True)
    print(f"Exact source recipe index complete: packages={len(rows)} package_bases={len(bases)}")


if __name__ == "__main__":
    main()
