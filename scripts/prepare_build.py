#!/usr/bin/env python3
"""Apply ArcDeck to its pinned upstream revision and set the debug identity."""
import argparse
import re
import subprocess
import sys
from pathlib import Path

UPSTREAM_SHA = "5ee271ea9545685417aecdd13b5ace033cdb7806"


def prepare(project, number):
    project = project.resolve()
    actual = subprocess.check_output(["git", "-C", str(project), "rev-parse", "HEAD"], text=True).strip()
    if actual != UPSTREAM_SHA:
        raise SystemExit(f"Upstream mismatch: expected {UPSTREAM_SHA}, got {actual}")
    patch = Path(__file__).resolve().parents[1]
    subprocess.run([sys.executable, str(patch / "install_workspace.py"), str(project)], check=True)
    prop = project / "gradle.properties"
    prop.write_text("\n".join(line for line in prop.read_text().splitlines()
        if not line.startswith("org.gradle.java.home") and "trustStoreType=Windows-ROOT" not in line) + "\n")
    gradle = project / "app/build.gradle.kts"
    source, count = re.subn(r'versionName = "[^"\n]+"', 'versionName = "1.3.3-arcdeck.1"', gradle.read_text(), count=1)
    if count != 1:
        raise SystemExit("Expected an upstream versionName")
    source, count = re.subn(r"versionCode = \d+", f"versionCode = {1000 + number}", source, count=1)
    if count != 1:
        raise SystemExit("Expected an upstream versionCode")
    gradle.write_text(source)
    label = project / "app/src/debug/res/values/arcdeck.xml"
    label.parent.mkdir(parents=True, exist_ok=True)
    label.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources><string name="app_name">ArcDeck Workspace</string></resources>\n')
    print(f"Prepared ArcDeck against {actual}, versionCode={1000 + number}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("project", type=Path)
    parser.add_argument("--build-number", type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.build_number < 2_000_000_000:
        parser.error("build number out of range")
    prepare(args.project, args.build_number)
