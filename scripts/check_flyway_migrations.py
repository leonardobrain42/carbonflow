#!/usr/bin/env python3
"""Block duplicate Flyway versions and mutations of base-branch migrations."""
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DIR = "backend/src/main/resources/db/migration"

def normalized(name: str) -> tuple[int, ...]:
    match = re.fullmatch(r"V([0-9]+(?:[._][0-9]+)*)__.+\.sql", name)
    if not match:
        raise ValueError("Invalid versioned migration: " + name)
    parts = [int(p) for p in re.split(r"[._]", match.group(1))]
    while len(parts) > 1 and parts[-1] == 0:
        parts.pop()
    return tuple(parts)

def main() -> None:
    seen = {}
    for path in sorted((ROOT / DIR).glob("V*.sql")):
        version = normalized(path.name)
        if version in seen:
            raise SystemExit(f"Duplicate Flyway version: {seen[version]} / {path.name}")
        seen[version] = path.name
    if len(sys.argv) > 1:
        diff = subprocess.check_output(
            ["git", "diff", "--name-status", "--no-renames", sys.argv[1],
             "HEAD", "--", DIR], cwd=ROOT, text=True)
        for line in diff.splitlines():
            status, path = line.split("\t", 1)
            if status != "A" and re.fullmatch(r"V.*\.sql", Path(path).name):
                raise SystemExit("Published migration changed: " + line)
    print("Flyway version/immutability guard OK")

if __name__ == "__main__":
    main()
