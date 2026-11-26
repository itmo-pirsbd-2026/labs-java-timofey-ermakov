#!/usr/bin/env python
"""Aggregate async-profiler collapsed stacks into a frame-level summary.

Usage: python -I summarize-collapsed.py <collapsed-file> [frame-substring ...]
Prints total samples and on-CPU share of frames matching each substring (leaf-only for '*Leaf' suffix logic not used; frame appears in stack => counted per stack once).
"""
import sys


def main() -> None:
    path = sys.argv[1]
    patterns = sys.argv[2:]
    total = 0
    totals = {p: 0 for p in patterns}
    for line in open(path, encoding="utf-8"):
        line = line.rstrip("\n")
        if not line:
            continue
        stack, _, count = line.rpartition(" ")
        count = int(count)
        total += count
        for p in patterns:
            if p in stack:
                totals[p] += count
    print(f"total samples: {total}")
    for p in patterns:
        share = 100.0 * totals[p] / total if total else 0.0
        print(f"{totals[p]:8d}  {share:6.2f}%  {p}")


if __name__ == "__main__":
    main()
