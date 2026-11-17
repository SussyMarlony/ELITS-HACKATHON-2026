#!/usr/bin/env python3
"""Checks that an APK's native libraries work on 16 KB page-size Android devices.

Implements the checks from https://developer.android.com/guide/practices/page-sizes for the
64-bit ABIs (arm64-v8a, x86_64):
  1. ELF alignment: every LOAD segment has p_align >= 16 KB (llvm-objdump shows "align 2**14").
  2. RELRO: if GNU_RELRO does not end on a 16 KB boundary, no writable LOAD data may share
     its last 16 KB page (the loader would make that data read-only -> SIGSEGV).
  3. Zip alignment: uncompressed .so entries start on a 16 KB boundary in the APK
     (what `zipalign -c -P 16` checks). Compressed entries are extracted at install time.

No NDK or build-tools needed. Usage:
  scripts/check-16kb.py                      # every APK under app/build/outputs/apk/
  scripts/check-16kb.py app-debug.apk ...    # specific APKs, or .so files / directories
Exit code 0 when everything passes, 1 otherwise.
"""
from __future__ import annotations

import struct
import sys
import zipfile
from pathlib import Path

PAGE_16K = 16 * 1024
ABIS_64 = ("arm64-v8a", "x86_64")
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


def program_headers(elf: bytes) -> list[tuple[int, int, int, int, int]]:
    """(type, flags, vaddr, memsz, align) for each program header of a 64-bit little-endian ELF."""
    if elf[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    if elf[4] != 2 or elf[5] != 1:
        raise ValueError("not a 64-bit little-endian ELF")
    phoff = struct.unpack_from("<Q", elf, 0x20)[0]
    phentsize, phnum = struct.unpack_from("<HH", elf, 0x36)
    headers = []
    for i in range(phnum):
        p_type, p_flags, _off, vaddr, _paddr, _filesz, memsz, align = struct.unpack_from(
            "<IIQQQQQQ", elf, phoff + i * phentsize)
        headers.append((p_type, p_flags, vaddr, memsz, align))
    return headers


def elf_problems(elf: bytes) -> list[str]:
    problems = []
    headers = program_headers(elf)
    loads = [h for h in headers if h[0] == PT_LOAD]
    for _t, _f, vaddr, _m, align in loads:
        if align < PAGE_16K:
            problems.append(f"LOAD at 0x{vaddr:x} aligned to {align} bytes (needs {PAGE_16K})")
    for _t, _f, rvaddr, rmemsz, _a in (h for h in headers if h[0] == PT_GNU_RELRO):
        end = rvaddr + rmemsz
        page_end = (end + PAGE_16K - 1) // PAGE_16K * PAGE_16K
        if end == page_end:
            continue
        for _t2, flags, vaddr, memsz, _a2 in loads:
            if not flags & PF_W:
                continue
            # Writable bytes in [end, page_end) would become read-only along with RELRO.
            if max(vaddr, end) < min(vaddr + memsz, page_end):
                problems.append(
                    f"GNU_RELRO ends at 0x{end:x}, sharing its 16 KB page with writable data "
                    f"(LOAD 0x{vaddr:x}-0x{vaddr + memsz:x})")
    return problems


def zip_data_offset(apk: Path, info: zipfile.ZipInfo) -> int:
    """Offset of the entry's data: local header (30 bytes) + file name + local extra field."""
    with apk.open("rb") as f:
        f.seek(info.header_offset)
        header = f.read(30)
    name_len, extra_len = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_len + extra_len


def check_apk(apk: Path) -> int:
    failures = 0
    print(f"{apk}")
    with zipfile.ZipFile(apk) as z:
        libs = [i for i in z.infolist()
                if i.filename.startswith("lib/") and i.filename.endswith(".so")]
        if not libs:
            print("  no native libraries")
        for info in libs:
            abi = info.filename.split("/")[1]
            if abi not in ABIS_64:
                print(f"  SKIP       {info.filename} (32-bit ABIs are not affected)")
                continue
            problems = []
            try:
                problems += elf_problems(z.read(info))
            except ValueError as e:
                problems.append(str(e))
            if info.compress_type == zipfile.ZIP_STORED:
                offset = zip_data_offset(apk, info)
                if offset % PAGE_16K:
                    problems.append(f"stored at APK offset {offset}, not a multiple of {PAGE_16K}")
            failures += report(info.filename, problems)
    return failures


def check_so(path: Path) -> int:
    try:
        problems = elf_problems(path.read_bytes())
    except ValueError as e:
        problems = [str(e)]
    return report(str(path), problems)


def report(name: str, problems: list[str]) -> int:
    if not problems:
        print(f"  ALIGNED    {name}")
        return 0
    print(f"  UNALIGNED  {name}")
    for p in problems:
        print(f"             - {p}")
    return 1


def main(args: list[str]) -> int:
    targets = [Path(a) for a in args]
    if not targets:
        root = Path(__file__).resolve().parent.parent / "app" / "build" / "outputs" / "apk"
        targets = sorted(root.rglob("*.apk"))
        if not targets:
            print(f"No APKs under {root}. Build first: ./gradlew :app:assembleDebug", file=sys.stderr)
            return 2
    failures = 0
    for t in targets:
        if t.is_dir():
            for so in sorted(t.rglob("*.so")):
                failures += check_so(so)
        elif t.suffix == ".apk":
            failures += check_apk(t)
        else:
            failures += check_so(t)
    print("16 KB check:", "PASS" if failures == 0 else f"FAIL ({failures} libraries)")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
