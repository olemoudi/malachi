#!/usr/bin/env python3
"""Generate a shields-style coverage badge from the aggregated JaCoCo CSV.

Self-contained (stdlib only) so CI needs no third-party action. Reads instruction
coverage across all rows and writes a flat SVG badge.
"""
import csv
import math
import sys
from pathlib import Path

CSV = Path("build/reports/jacoco/jacocoAggregatedReport/jacocoAggregatedReport.csv")
OUT = Path(".github/badges/coverage.svg")


def coverage_percent() -> float:
    missed = covered = 0
    with CSV.open() as f:
        for row in csv.DictReader(f):
            missed += int(row["INSTRUCTION_MISSED"])
            covered += int(row["INSTRUCTION_COVERED"])
    total = missed + covered
    return 100.0 * covered / total if total else 0.0


def color(pct: float) -> str:
    for threshold, c in ((90, "#4c1"), (80, "#97ca00"), (70, "#dfb317"), (60, "#fe7d37")):
        if pct >= threshold:
            return c
    return "#e05d44"


# Advance widths in pixels, measured from DejaVu Sans at 11px -- the font this SVG actually
# gets wherever Verdana and Geneva are absent, which is every Linux renderer.
#
# This used to be a flat 6.5px per character, and that is what printed the digits of "91%" on
# top of one another. The average is fair for lowercase ("coverage" came out 52 against a real
# 51.06, an invisible pixel of stretch) and badly wrong for anything else: "%" is 10.45px, over
# half again the assumption. So the value cell was built 19px wide to hold 24.45px of glyphs,
# and `textLength` has to obey -- with the default `lengthAdjust` it may only alter the spacing,
# so it used *negative* spacing and the glyphs overlapped.
#
# Hence the rule this table exists to keep: never under-measure. A cell a pixel too wide spreads
# the gaps by a fraction nobody can see; a cell a pixel too narrow overlaps the text. Unknown
# characters are therefore charged the widest glyph here rather than an average.
_GLYPH_WIDTHS = {
    'a': 6.73, 'b': 6.98, 'c': 6.05, 'd': 6.98, 'e': 6.77, 'f': 3.88, 'g': 6.98,
    'h': 6.97, 'i': 3.06, 'j': 3.06, 'k': 6.38, 'l': 3.06, 'm': 10.72, 'n': 6.97,
    'o': 6.73, 'p': 6.98, 'q': 6.98, 'r': 4.52, 's': 5.73, 't': 4.31, 'u': 6.97,
    'v': 6.52, 'w': 9.00, 'x': 6.52, 'y': 6.52, 'z': 5.78,
    '0': 7.00, '1': 7.00, '2': 7.00, '3': 7.00, '4': 7.00,
    '5': 7.00, '6': 7.00, '7': 7.00, '8': 7.00, '9': 7.00,
    '%': 10.45, '.': 3.50, ' ': 3.50,
}
_WIDEST_GLYPH = max(_GLYPH_WIDTHS.values())


def text_width(s: str) -> int:
    """Width of the cell holding `s`: the text's own width, rounded up, plus 10px of padding."""
    return math.ceil(sum(_GLYPH_WIDTHS.get(c, _WIDEST_GLYPH) for c in s)) + 10


def badge(pct: float) -> str:
    label, value = "coverage", f"{pct:.0f}%"
    lw, rw = text_width(label), text_width(value)
    w = lw + rw
    # Cell centres, in the 10x coordinate space the text is drawn in. Doubled before halving so
    # an odd cell width does not lose half a pixel to integer division.
    lx, rx = lw * 10 // 2, (2 * lw + rw) * 10 // 2
    ltl, rtl = (lw - 10) * 10, (rw - 10) * 10
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="20" role="img" aria-label="{label}: {value}">
<title>{label}: {value}</title>
<linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
<clipPath id="r"><rect width="{w}" height="20" rx="3" fill="#fff"/></clipPath>
<g clip-path="url(#r)">
<rect width="{lw}" height="20" fill="#555"/>
<rect x="{lw}" width="{rw}" height="20" fill="{color(pct)}"/>
<rect width="{w}" height="20" fill="url(#s)"/>
</g>
<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" text-rendering="geometricPrecision" font-size="110">
<text x="{lx}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="{ltl}">{label}</text>
<text x="{lx}" y="140" transform="scale(.1)" textLength="{ltl}">{label}</text>
<text x="{rx}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)" textLength="{rtl}">{value}</text>
<text x="{rx}" y="140" transform="scale(.1)" textLength="{rtl}">{value}</text>
</g>
</svg>
"""


def main() -> int:
    if not CSV.exists():
        print(f"coverage CSV not found at {CSV}; run :jacocoAggregatedReport first", file=sys.stderr)
        return 1
    pct = coverage_percent()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(badge(pct))
    print(f"coverage {pct:.1f}% -> {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
