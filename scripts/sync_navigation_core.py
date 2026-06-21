#!/usr/bin/env python3
"""Synchronize the canonical shared NIMS navigation core into the extension."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "shared" / "nims-web" / "nimsReportCore.js"
TARGET = ROOT / "extension" / "src" / "navigationCore.js"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if extension copy differs")
    args = parser.parse_args()
    source = SOURCE.read_bytes()
    if args.check:
        if not TARGET.exists() or TARGET.read_bytes() != source:
            print("extension/src/navigationCore.js is out of sync with shared/nims-web/nimsReportCore.js", file=sys.stderr)
            return 1
        return 0
    TARGET.write_bytes(source)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
