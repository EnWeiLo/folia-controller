#!/usr/bin/env python3
"""Cuts the two 墨染 fonts that Google Fonts doesn't have into web fonts that load only the
characters a song needs, the way Google Fonts serves CJK fonts.

  源流明體 GenRyuMin2 TW  (ButTaiwan/genryu-font, SIL OFL 1.1)
  全字庫正楷體 TW-Kai      (全字庫 CNS11643, SIL OFL 1.1 or 政府資料開放授權條款)

Each font is split along Google Fonts' own slices for Noto Serif TC (ordered by how common the
characters are), plus one slice for whatever the font has beyond them. Output:
  <out>/<id>/font.css  (@font-face per slice with unicode-range)
  <out>/<id>/<n>.woff2
The CI workflow fonts.yml publishes <out> to the `fonts` branch, served by jsDelivr.

Usage: build-webfonts.py <genryu.otf> <tw-kai.ttf> <google-css.txt> <out dir>
"""
import os
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

FONTS = [
    # id, file arg index, css family, weight
    ("genryumin", 1, "GenRyuMin2 TW", 400),
    ("twkai", 2, "TW-Kai", 400),
]

# Characters lyrics use: Latin, punctuation, CJK symbols, kana, CJK ideographs (+Ext A), full width.
WANTED = [
    (0x20, 0x7E), (0xA0, 0x17F), (0x2000, 0x206F), (0x2190, 0x21FF), (0x2460, 0x24FF), (0x25A0, 0x26FF),
    (0x3000, 0x30FF), (0x3100, 0x312F), (0x31A0, 0x31BF), (0x3200, 0x33FF), (0x3400, 0x4DBF),
    (0x4E00, 0x9FFF), (0xF900, 0xFAFF), (0xFE30, 0xFE4F), (0xFF00, 0xFFEF),
]


def parse_ranges(text):
    """unicode-range lists from Google's CSS, in the order they appear."""
    out = []
    for block in re.findall(r"unicode-range:\s*([^;]+);", text):
        cps = set()
        for part in block.split(","):
            part = part.strip().upper().replace("U+", "")
            if not part:
                continue
            if "-" in part:
                a, b = part.split("-")
                cps.update(range(int(a, 16), int(b, 16) + 1))
            elif "?" in part:
                lo, hi = int(part.replace("?", "0"), 16), int(part.replace("?", "F"), 16)
                cps.update(range(lo, hi + 1))
            else:
                cps.add(int(part, 16))
        out.append(cps)
    return out


def to_ranges(cps):
    cps = sorted(cps)
    out, start, prev = [], None, None
    for c in cps:
        if start is None:
            start = prev = c
        elif c == prev + 1:
            prev = c
        else:
            out.append((start, prev))
            start = prev = c
    if start is not None:
        out.append((start, prev))
    return ", ".join("U+%X" % a if a == b else "U+%X-%X" % (a, b) for a, b in out)


def main():
    if len(sys.argv) != 5:
        print(__doc__)
        return 2
    google = parse_ranges(open(sys.argv[3], encoding="utf-8").read())
    print("google slices:", len(google))
    out_dir = sys.argv[4]
    wanted = set()
    for a, b in WANTED:
        wanted.update(range(a, b + 1))
    for fid, arg, family, weight in FONTS:
        path = sys.argv[arg]
        font = TTFont(path, fontNumber=0, lazy=True)
        have = set(font.getBestCmap().keys()) & wanted
        font.close()
        slices, used = [], set()
        for cps in google:
            s = (cps & have) - used
            if s:
                slices.append(s)
                used |= s
        rest = sorted(have - used)
        for i in range(0, len(rest), 1500):
            slices.append(set(rest[i:i + 1500]))
        d = os.path.join(out_dir, fid)
        os.makedirs(d, exist_ok=True)
        css, total = [], 0
        for n, s in enumerate(slices):
            opts = subset.Options()
            opts.flavor = "woff2"
            opts.layout_features = ["*"]
            opts.name_IDs = ["*"]
            opts.name_languages = ["*"]
            opts.notdef_outline = True
            opts.hinting = False
            opts.desubroutinize = True
            opts.font_number = 0
            f = subset.load_font(path, opts, dontLoadGlyphNames=True)
            sub = subset.Subsetter(opts)
            sub.populate(unicodes=s)
            sub.subset(f)
            name = "%d.woff2" % n
            subset.save_font(f, os.path.join(d, name), opts)
            total += os.path.getsize(os.path.join(d, name))
            css.append(
                "@font-face{font-family:'%s';font-style:normal;font-weight:%d;font-display:swap;"
                "src:url(%s) format('woff2');unicode-range:%s}" % (family, weight, name, to_ranges(s))
            )
        with open(os.path.join(d, "font.css"), "w", encoding="utf-8") as fh:
            fh.write("\n".join(css) + "\n")
        print("%s: %d glyphs in %d slices, %.1f MB" % (fid, len(have), len(slices), total / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
