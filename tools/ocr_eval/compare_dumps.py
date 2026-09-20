"""Compare two app text dumps page by page (before vs after a pipeline change)."""
import json, sys, os
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))


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


def norm(t):
    return " ".join(t.split())


def wer(ref, hyp):
    r, h = norm(ref).split(), norm(hyp).split()
    return lev(r, h) / max(len(r), 1)


def orderfree(ref, hyp):
    r, h = norm(ref).split(), norm(hyp).split()
    cr, ch = Counter(r), Counter(h)
    return (sum((cr - ch).values()) + sum((ch - cr).values())) / 2 / max(len(r), 1)


def load(p):
    return {r["image"]: r for r in json.load(open(p, encoding="utf-8"))}


def main():
    before = load(sys.argv[1])
    after = load(sys.argv[2])
    common = [k for k in after if k in before]
    print(f"{'page':26s} {'CER before':>11s} {'CER after':>10s} {'delta':>8s} "
          f"{'WER before':>11s} {'WER after':>10s}")
    print("-" * 82)
    tb = ta = tw_b = tw_a = n = 0
    rows = []
    for k in sorted(common):
        b, a = before[k], after[k]
        ref = b["reference"]
        cb, ca = b["corrected_cer"], a["corrected_cer"]
        wb, wa = wer(ref, b["corrected"]), wer(ref, a["corrected"])
        chars = b["expected_chars"]
        words = len(norm(ref).split())
        tb += cb * chars; ta += ca * chars; n += chars
        tw_b += wb * words; tw_a += wa * words
        rows.append((k, cb, ca, wb, wa, words))
        flag = "  <-- better" if ca < cb - 0.001 else ("  <-- worse" if ca > cb + 0.001 else "")
        print(f"{k[:26]:26s} {cb*100:10.2f}% {ca*100:9.2f}% {(ca-cb)*100:+7.2f} "
              f"{wb*100:10.2f}% {wa*100:9.2f}%{flag}")
    W = sum(r[5] for r in rows)
    print("-" * 82)
    print(f"{'CORPUS':26s} {tb/n*100:10.2f}% {ta/n*100:9.2f}% {(ta-tb)/n*100:+7.2f} "
          f"{tw_b/W*100:10.2f}% {tw_a/W*100:9.2f}%")
    better = sum(1 for r in rows if r[2] < r[1] - 0.001)
    worse = sum(1 for r in rows if r[2] > r[1] + 0.001)
    print(f"pages improved {better}, unchanged {len(rows)-better-worse}, made worse {worse}")

    print("\n--- how much of the remaining word error is still ordering? ---")
    for k in sorted(common):
        ref = before[k]["reference"]
        w_a = wer(ref, after[k]["corrected"])
        o_a = orderfree(ref, after[k]["corrected"])
        share = (1 - o_a / w_a) * 100 if w_a > 1e-9 else 0.0
        print(f"  {k[:26]:26s} WER {w_a*100:6.2f}%  order-free {o_a*100:6.2f}%  "
              f"ordering share {share:5.0f}%")


if __name__ == "__main__":
    main()
