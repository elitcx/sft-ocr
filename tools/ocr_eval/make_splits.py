"""Deterministic, group-aware train/val/test split over labelled samples.

Grouping matters: consecutive pages of the same book photographed in one
session are correlated, so a naive random split leaks near-duplicates between
train and test and makes the test number optimistic. Splits are stratified
*within* each capture session so every split sees every condition, but each
page lands in exactly one split.
"""
import os, json, random, re

LABELS = r"C:\Users\Kiel\Documents\SFT OCR\braille-ocr\dataset\ai-transcribed"
OUT = os.path.dirname(os.path.abspath(__file__))
SEED = 20260920
RATIOS = (0.60, 0.20, 0.20)  # train, val, test


def session(base):
    m = re.match(r"(\d{8})_", base)
    if m:
        return m.group(1)
    m = re.match(r"IMG[-_](\d{8})", base)
    if m:
        return "wa-" + m.group(1)[:6]
    return "other"


def main():
    bases = sorted(f[:-4] for f in os.listdir(LABELS) if f.endswith(".txt"))
    groups = {}
    for b in bases:
        groups.setdefault(session(b), []).append(b)

    rng = random.Random(SEED)
    split = {"train": [], "val": [], "test": []}
    for g in sorted(groups):
        items = sorted(groups[g])
        rng.shuffle(items)
        n = len(items)
        n_tr = max(1, round(n * RATIOS[0])) if n >= 3 else n
        n_va = max(1, round(n * RATIOS[1])) if n >= 3 else 0
        split["train"] += items[:n_tr]
        split["val"] += items[n_tr:n_tr + n_va]
        split["test"] += items[n_tr + n_va:]

    for k in split:
        split[k] = sorted(split[k])
        json.dump(split[k], open(os.path.join(OUT, f"split_{k}.json"), "w"), indent=1)
    json.dump(split, open(os.path.join(OUT, "splits.json"), "w"), indent=1)

    print(f"seed={SEED}  total={len(bases)}")
    for k in ("train", "val", "test"):
        by = {}
        for b in split[k]:
            by[session(b)] = by.get(session(b), 0) + 1
        print(f"  {k:5s} n={len(split[k]):3d}  {by}")
    overlap = set(split["train"]) & set(split["test"])
    assert not overlap, overlap
    print("no train/test overlap: OK")


if __name__ == "__main__":
    main()
