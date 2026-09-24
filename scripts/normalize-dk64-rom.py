#!/usr/bin/env python3
"""Normalize a DK64 N64 ROM byte order to canonical big-endian .z64.

Accepted input byte orders:
  z64: 80 37 12 40 (big-endian)
  v64: 37 80 40 12 (byte-swapped 16-bit)
  n64: 40 12 37 80 (little-endian 32-bit)

The output is accepted only when it matches DK64 NTSC-U 1.0 exactly.
"""
from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import sys

EXPECTED_SHA1 = "cf806ff2603640a748fca5026ded28802f1f4a50"
MAGIC_Z64 = bytes.fromhex("80371240")
MAGIC_V64 = bytes.fromhex("37804012")
MAGIC_N64 = bytes.fromhex("40123780")


def normalize(data: bytes) -> tuple[bytes, str]:
    if len(data) < 4:
        raise ValueError("ROM is too small")
    magic = data[:4]
    if magic == MAGIC_Z64:
        return data, "z64"
    if magic == MAGIC_V64:
        if len(data) % 2:
            raise ValueError("v64 ROM has an odd byte count")
        out = bytearray(data)
        out[0::2], out[1::2] = data[1::2], data[0::2]
        return bytes(out), "v64"
    if magic == MAGIC_N64:
        if len(data) % 4:
            raise ValueError("n64 ROM size is not divisible by 4")
        out = bytearray(len(data))
        for i in range(0, len(data), 4):
            out[i:i + 4] = data[i:i + 4][::-1]
        return bytes(out), "n64"
    raise ValueError(f"unknown N64 ROM byte order/magic: {magic.hex()}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("input", type=Path)
    ap.add_argument("output", type=Path)
    args = ap.parse_args()

    try:
        raw = args.input.read_bytes()
        canonical, source_order = normalize(raw)
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    sha1 = hashlib.sha1(canonical).hexdigest()
    if sha1 != EXPECTED_SHA1:
        print(
            f"error: ROM is not DK64 NTSC-U 1.0 after byte-order normalization "
            f"(sha1 {sha1}, expected {EXPECTED_SHA1})",
            file=sys.stderr,
        )
        return 3

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(canonical)
    print(f"ROM normalized: {source_order} -> z64")
    print(f"ROM sha1: {sha1}")
    print(f"ROM output: {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
