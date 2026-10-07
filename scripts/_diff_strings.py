import re
import glob
import os
import sys

root = sys.argv[1] if len(sys.argv) > 1 else "tv/src/main/res"


def keys(path):
    with open(path, encoding="utf-8") as f:
        return set(re.findall(r'<string\s+name="([^"]+)"', f.read()))


base = keys(os.path.join(root, "values", "strings.xml"))
print("base keys:", len(base))
for d in sorted(glob.glob(os.path.join(root, "values-*"))):
    if not os.path.isdir(d):
        continue
    name = os.path.basename(d)
    p = os.path.join(d, "strings.xml")
    if not os.path.exists(p):
        print(name, "NO FILE")
        continue
    k = keys(p)
    missing = sorted(base - k)
    extra = sorted(k - base)
    print(name, "have", len(k), "missing", len(missing))
    if missing:
        for m in missing:
            print("   ", m)
    if extra:
        print("    EXTRA:", extra)
