#!/usr/bin/env python3
"""Strip a leading emoji from <string> values in every locale.

Why: this app draws icons with vector drawables and tints them from the theme.
An emoji baked into the string cannot be tinted, cannot be sized, cannot be
aligned, and has to be re-pasted into all 11 locale files. Where the widget
already carries app:icon it also produces two icons in one button.

The script only touches a value whose *first* character is an emoji, so it can
never eat text. Numbers, placeholders and everything else are untouched.

Idempotent: a second run finds nothing and writes nothing.

Usage:
    python scripts/_strip_leading_emoji.py                      # report only
    python scripts/_strip_leading_emoji.py --apply              # rewrite all
    python scripts/_strip_leading_emoji.py --apply --only a,b   # rewrite only
"""

import glob
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "mobile", "src", "main", "res"))

EMOJI_CLASS = (
    r"[\U0001F000-\U0001FAFF\u2190-\u21FF\u2300-\u27BF\u2B00-\u2BFF\uFE0F\u200D]"
)

# value start -> optional spaces -> optional more emoji/space -> rest
PATTERN = re.compile(
    r'(<string name="([^"]+)">)[\s]*' + EMOJI_CLASS + r'+[\s]*'
)


def main() -> int:
    apply_changes = "--apply" in sys.argv

    only: set[str] | None = None
    if "--only" in sys.argv:
        raw = sys.argv[sys.argv.index("--only") + 1]
        only = {k.strip() for k in raw.split(",") if k.strip()}

    files = sorted(glob.glob(os.path.join(RES, "values*", "strings.xml")))
    if not files:
        print(f"no strings.xml under {RES}", file=sys.stderr)
        return 1

    # key -> set of locales that would change
    hits: dict[str, set[str]] = {}
    total = 0

    for path in files:
        locale = os.path.basename(os.path.dirname(path))
        with open(path, encoding="utf-8") as fh:
            src = fh.read()

        for match in PATTERN.finditer(src):
            key = match.group(2)
            if only is not None and key not in only:
                continue
            hits.setdefault(key, set()).add(locale)

        if apply_changes:
            replaced = 0

            def repl(m: re.Match) -> str:
                nonlocal replaced
                key = m.group(2)
                if only is not None and key not in only:
                    return m.group(0)
                replaced += 1
                return m.group(1)

            new = PATTERN.sub(repl, src)
            if replaced:
                with open(path, "w", encoding="utf-8", newline="") as fh:
                    fh.write(new)
                total += replaced

    if not hits:
        print("nothing matched — no strings start with an emoji" + (
            " among the requested keys" if only else ""))
        return 0

    print(f"{len(hits)} distinct string keys start with an emoji:\n")
    for key in sorted(hits):
        locales = len(hits[key])
        print(f"  {key:<34} {locales:2d} locale(s)")

    if apply_changes:
        print(f"\nrewrote {total} string(s)")
    else:
        print("\nreport only — re-run with --apply to rewrite")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
