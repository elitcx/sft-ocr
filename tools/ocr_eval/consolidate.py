"""Consolidate every measurement into one results table."""
import os, re, json, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PAT = re.compile(r"(\S+\.jpg) \[(recognizer only|corrected)\]: CER=([\d.]+) WER=([\d.]+) "
                 r"charAcc=([\d.]+) wordAcc=([\d.]+) expectedChars=(\d+) expectedWords=(\d+)")


def parse(logname):
    p = os.path.join(HERE, logname)
    if not os.path.exists(p):
        return {}
    rows = {}
    for m in PAT.finditer(open(p, encoding="utf8", errors="replace").read()):
        name, mode, cer, wer, ca, wa, ec, ew = m.groups()
        rows.setdefault(name[:-4], {})[mode] = {
            "cer": float(cer), "wer": float(wer),
            "chars": int(ec), "words": int(ew)}
    return rows


def agg(rows, names, mode):
    rs = [rows[b][mode] for b in names if b in rows and mode in rows[b]]
    if not rs:
        return None
    c = sum(r["chars"] for r in rs) or 1
    w = sum(r["words"] for r in rs) or 1
    return {"cer": sum(r["cer"] * r["chars"] for r in rs) / c,
            "wer": sum(r["wer"] * r["words"] for r in rs) / w,
            "n": len(rs), "chars": c}


def split(name):
    p = os.path.join(HERE, f"split_{name}.json")
    return set(json.load(open(p))) if os.path.exists(p) else set()


def main():
    splits = {k: split(k) for k in ("train", "val", "test", "holdout")}
    shipped = parse("harness_shipped.log")
    finetuned = parse("harness_finetuned.log")
    full = parse("full_harness.log")

    print("=" * 78)
    print("APP-LEVEL ACCURACY (real pipeline: ML Kit + Tesseract + correction layer)")
    print("=" * 78)
    print(f"{'run / group':30s} {'n':>3s} {'chars':>7s} {'CER raw':>9s} {'CER corr':>9s} {'WER corr':>9s}")
    for label, rows, names in [
        ("full set (shipped model)", full, set(full)),
        ("  train split", full, splits["train"]),
        ("  val split", full, splits["val"]),
        ("  test split", full, splits["test"]),
        ("  committed seed GT", full, set(full) - set().union(*splits.values())),
        ("test+holdout SHIPPED", shipped, set(shipped)),
        ("  test split", shipped, splits["test"]),
        ("  OOD holdout", shipped, splits["holdout"]),
        ("test+holdout FINE-TUNED", finetuned, set(finetuned)),
        ("  test split", finetuned, splits["test"]),
        ("  OOD holdout", finetuned, splits["holdout"]),
    ]:
        a = agg(rows, names, "recognizer only")
        b = agg(rows, names, "corrected")
        if not a or not b:
            continue
        print(f"{label:30s} {a['n']:3d} {a['chars']:7,d} {a['cer']*100:8.2f}% "
              f"{b['cer']*100:8.2f}% {b['wer']*100:8.2f}%")

    if shipped and finetuned:
        common = sorted(set(shipped) & set(finetuned))
        s = agg(shipped, common, "corrected"); f = agg(finetuned, common, "corrected")
        print("\n" + "-" * 78)
        print(f"A/B on identical {len(common)} pages: shipped CER {s['cer']*100:.2f}% -> "
              f"fine-tuned CER {f['cer']*100:.2f}%  (delta {(f['cer']-s['cer'])*100:+.2f} pp)")
        print(f"                              shipped WER {s['wer']*100:.2f}% -> "
              f"fine-tuned WER {f['wer']*100:.2f}%  (delta {(f['wer']-s['wer'])*100:+.2f} pp)")
        print("\nper-page (corrected CER):")
        for b in common:
            cs = shipped[b]["corrected"]["cer"] * 100
            cf = finetuned[b]["corrected"]["cer"] * 100
            flag = "  <-- worse" if cf > cs + 0.05 else ("  <-- better" if cf < cs - 0.05 else "")
            print(f"  {b:26s} {cs:6.2f}% -> {cf:6.2f}%{flag}")


if __name__ == "__main__":
    main()
