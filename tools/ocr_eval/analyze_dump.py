"""Turn the app's dumped text into an error diagnosis.

A CER number says a page scored badly. This says why: how much of the error is
insertion (text the app read that the reference does not contain — a facing page
or leader dots), how much is deletion (text it missed), and how much is genuine
substitution (it read the right place and got the characters wrong).
"""
import json, re, sys, difflib, os

HERE = os.path.dirname(os.path.abspath(__file__))
LEADER = re.compile(r"\.{2,}")            # contents-page dot leaders
DOTSPACE = re.compile(r"(?:\.\s*){3,}")   # leaders the recognizer spaced out


def norm(t):
    return " ".join(t.split())


def strip_leaders(t):
    return norm(DOTSPACE.sub(" ", LEADER.sub(" ", t)))


def lev(a, b):
    if not a: return len(b)
    if not b: return len(a)
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j-1] + (a[i-1] != b[j-1]), prev[j] + 1, cur[j-1] + 1)
        prev = cur
    return prev[len(b)]


def cer(ref, hyp):
    r, h = norm(ref), norm(hyp)
    return lev(list(r), list(h)) / max(len(r), 1)


def breakdown(ref, hyp):
    """Character-level insert/delete/replace counts via difflib opcodes."""
    r, h = norm(ref), norm(hyp)
    sm = difflib.SequenceMatcher(a=r, b=h, autojunk=False)
    ins = dele = rep = 0
    runs = []
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "insert":
            ins += (j2 - j1)
            runs.append((j2 - j1, h[j1:j2]))
        elif tag == "delete":
            dele += (i2 - i1)
        elif tag == "replace":
            rep += max(i2 - i1, j2 - j1)
    runs.sort(reverse=True)
    return ins, dele, rep, len(r), runs[:3]


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "text-dump", "dump.json")
    recs = json.load(open(path, encoding="utf-8"))
    print(f"pages dumped: {len(recs)}\n")
    print(f"{'page':26s} {'CER':>7s} {'CER-noLead':>11s} {'ins':>6s} {'del':>6s} {'sub':>6s} {'refchars':>9s}")
    print("-" * 78)
    tot = {"ref": 0, "ins": 0, "del": 0, "rep": 0}
    rows = []
    for r in recs:
        ref, hyp = r["reference"], r["corrected"]
        c = cer(ref, hyp)
        c_nl = cer(strip_leaders(ref), strip_leaders(hyp))
        ins, dele, rep, n, runs = breakdown(ref, hyp)
        rows.append((r["image"], c, c_nl, ins, dele, rep, n, runs, ref, hyp))
        tot["ref"] += n; tot["ins"] += ins; tot["del"] += dele; tot["rep"] += rep
        print(f"{r['image'][:26]:26s} {c*100:6.2f}% {c_nl*100:10.2f}% "
              f"{ins:6d} {dele:6d} {rep:6d} {n:9d}")
    print("-" * 78)
    n = tot["ref"] or 1
    print(f"{'TOTAL':26s} {'':7s} {'':11s} {tot['ins']:6d} {tot['del']:6d} {tot['rep']:6d} {n:9d}")
    print(f"\nerror composition: insertions {tot['ins']/n*100:.1f}%  "
          f"deletions {tot['del']/n*100:.1f}%  substitutions {tot['rep']/n*100:.1f}% "
          f"(as % of reference length)")

    print("\n=== largest inserted runs (text the app read that the reference lacks) ===")
    for img, c, c_nl, ins, dele, rep, n, runs, ref, hyp in sorted(rows, key=lambda x: -x[3])[:6]:
        print(f"\n{img}  CER {c*100:.2f}%  inserted {ins} chars")
        for ln, txt in runs:
            if ln >= 15:
                print(f"   +{ln:4d}  {txt[:100]!r}")

    print("\n=== biggest gain from ignoring leader dots ===")
    for img, c, c_nl, *_ in sorted(rows, key=lambda x: (x[2] - x[1]))[:5]:
        if c - c_nl > 0.005:
            print(f"  {img:26s} {c*100:6.2f}%  ->  {c_nl*100:6.2f}%   ({(c-c_nl)*100:+.2f} pp)")


if __name__ == "__main__":
    main()
