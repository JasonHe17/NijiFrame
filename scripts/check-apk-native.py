#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Check packaged 64-bit ELF load alignment/RELRO layout; not a device execution test.

Run zipalign -c -P 16 4 separately for APK entry alignment. See:
https://developer.android.com/guide/practices/page-sizes
"""
import argparse
import json
import struct
import zipfile


def check(apk):
    reports = []
    with zipfile.ZipFile(apk) as archive:
        for name in sorted(archive.namelist()):
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            data = archive.read(name)
            if data[:6] != b"\x7fELF\x02\x01":
                raise ValueError(f"{name}: expected little-endian ELF64")
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            if size < 56 or offset + size * count > len(data):
                raise ValueError(f"{name}: invalid program headers")
            loads = []
            writable = []
            relro = []
            for index in range(count):
                kind, flags, file_offset, address, _, _, memory_size, alignment = struct.unpack_from(
                    "<IIQQQQQQ", data, offset + index * size
                )
                if kind == 1:  # PT_LOAD
                    loads.append(alignment)
                    if flags & 2 and memory_size:
                        writable.append((address, address + memory_size))
                    if alignment < 16384 or alignment & (alignment - 1) or (address - file_offset) % 16384:
                        raise ValueError(f"{name}: PT_LOAD is not 16 KiB aligned")
                elif kind == 0x6474E552:  # PT_GNU_RELRO
                    if memory_size:
                        relro.append((address, address + memory_size))
            if not loads:
                raise ValueError(f"{name}: no PT_LOAD segments")
            # Bionic rounds RELRO protection out to pages. An unaligned end is
            # safe when the next writable bytes are on another page; testing
            # end % 16384 alone incorrectly rejects padded LOAD layouts.
            # https://android.googlesource.com/platform/bionic/+/android16-qpr2-release/linker/linker_phdr.cpp
            for start, end in relro:
                protected_start, protected_end = start // 16384 * 16384, (end + 16383) // 16384 * 16384
                for write_start, write_end in writable:
                    remaining = [(write_start, write_end)]
                    for read_start, read_end in relro:
                        pieces = []
                        for left, right in remaining:
                            if right <= read_start or left >= read_end:
                                pieces.append((left, right))
                            else:
                                if left < read_start:
                                    pieces.append((left, read_start))
                                if right > read_end:
                                    pieces.append((read_end, right))
                        remaining = pieces
                    if any(max(left, protected_start) < min(right, protected_end) for left, right in remaining):
                        raise ValueError(f"{name}: rounded RELRO protection overlaps writable data")
            reports.append({"library": name, "load_alignments": loads, "relro_ranges": relro})
    if not reports:
        raise ValueError("APK contains no native libraries")
    return reports


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    args = parser.parse_args()
    print(json.dumps(check(args.apk), indent=2))
