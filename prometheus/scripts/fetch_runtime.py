#!/usr/bin/env python3
"""Fetch the exact, unmodified upstream runtime used by this build."""
from pathlib import Path
import subprocess

REVISION = "afe77e18fadc2e673c6a6299495a443e17378ce4"
root = Path(__file__).resolve().parents[1]
target = root / "app/src/main/vendor/llama.cpp"
target.parent.mkdir(parents=True, exist_ok=True)
if not (target / ".git").exists():
    subprocess.run(["git", "init", str(target)], check=True)
    subprocess.run(["git", "-C", str(target), "remote", "add", "origin",
                    "https://github.com/ggml-org/llama.cpp.git"], check=True)
current = subprocess.run(["git", "-C", str(target), "rev-parse", "HEAD"],
                         text=True, capture_output=True).stdout.strip()
if current != REVISION:
    subprocess.run(["git", "-C", str(target), "fetch", "--depth", "1", "origin", REVISION], check=True)
    subprocess.run(["git", "-C", str(target), "checkout", "--detach", REVISION], check=True)
actual = subprocess.check_output(["git", "-C", str(target), "rev-parse", "HEAD"], text=True).strip()
assert actual == REVISION, actual
print("llama.cpp", actual)
