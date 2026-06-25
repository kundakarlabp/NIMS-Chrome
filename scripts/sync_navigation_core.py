#!/usr/bin/env python3
"""Synchronize shared NIMS sources into the locations that consume them.

- shared/nims-web/nimsReportCore.js  -> extension/src/navigationCore.js
- extension/src/contentUtils.js       -> shared/nims-web/contentUtils.js
  (so the Android WebView, whose assets include shared/nims-web, ships the
   exact same report-row/URL logic the Chrome extension uses)
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# (canonical source, generated copy)
PAIRS = [
    (ROOT / "shared" / "nims-web" / "nimsReportCore.js", ROOT / "extension" / "src" / "navigationCore.js"),
    (ROOT / "extension" / "src" / "contentUtils.js", ROOT / "shared" / "nims-web" / "contentUtils.js"),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="fail if any generated copy differs")
    args = parser.parse_args()
    failed = False
    for source, target in PAIRS:
        data = source.read_bytes()
        if args.check:
            if not target.exists() or target.read_bytes() != data:
                rel_t = target.relative_to(ROOT)
                rel_s = source.relative_to(ROOT)
                print(f"{rel_t} is out of sync with {rel_s}", file=sys.stderr)
                failed = True
        else:
            target.write_bytes(data)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
