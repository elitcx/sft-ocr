"""Run Tesseract over labelled samples and score CER/WER.

Mirrors ocr-core/accuracy/ErrorRate.kt exactly: normalize = trim + collapse
whitespace runs to one space; CER = levenshtein(chars)/len(expected chars);
WER = levenshtein(space-split words)/len(expected words).
"""
import os, sys, json, subprocess, tempfile, argparse, time
from PIL import Image, ImageOps, ImageEnhance, ImageFilter

SRC = r"C:\Users\Kiel\Downloads\braille testing"
LABELS = r"C:\Users\Kiel\Documents\SFT OCR\braille-ocr\dataset\ai-transcribed"
TESS = r"C:\Program Files\Tesseract-OCR\tesseract.exe"
TESSDATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tessdata")


def normalize(t):
    return " ".join(t.split())


def levenshtein(a, b):
    if not a:
        return len(b)
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        ai = a[i - 1]
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j - 1] + (ai != b[j - 1]), prev[j] + 1, cur[j - 1] + 1)
        prev = cur
    return prev[len(b)]


def rate(dist, exp_n, act_n):
    if exp_n > 0:
        return dist / exp_n
    return 1.0 if act_n > 0 else 0.0


def compare(expected, actual):
    e, a = normalize(expected), normalize(actual)
    ew, aw = [w for w in e.split(" ") if w], [w for w in a.split(" ") if w]
    return {
        "cer": rate(levenshtein(list(e), list(a)), len(e), len(a)),
        "wer": rate(levenshtein(ew, aw), len(ew), len(aw)),
        "expected_chars": len(e),
        "expected_words": len(ew),
    }


def source_image(base):
    for ext in (".jpg", ".JPG", ".jpeg"):
        p = os.path.join(SRC, base + ext)
        if os.path.exists(p):
            return p
    return None


def preprocess(im, mode):
    """Mirrors the choices ImagePreprocessor can make, so config sweeps are meaningful."""
    if mode == "rgb":
        return im.convert("RGB")
    g = im.convert("L")
    if mode == "gray":
        return g
    if mode == "autocontrast":
        return ImageOps.autocontrast(g, cutoff=1)
    if mode == "contrast":
        return ImageEnhance.Contrast(g).enhance(1.6)
    if mode == "auto+contrast":
        return ImageEnhance.Contrast(ImageOps.autocontrast(g, cutoff=1)).enhance(1.6)
    if mode == "sharpen":
        return ImageOps.autocontrast(g.filter(ImageFilter.UnsharpMask(2, 120, 3)), cutoff=1)
    raise ValueError(mode)


def run_tesseract(img_path, lang, psm, max_px, tessdata=None, prep="gray"):
    im = ImageOps.exif_transpose(Image.open(img_path))
    im = preprocess(im, prep)
    if max_px:
        im.thumbnail((max_px, max_px), Image.LANCZOS)
    fd, tmp = tempfile.mkstemp(suffix=".png")
    os.close(fd)
    im.save(tmp)
    env = dict(os.environ, TESSDATA_PREFIX=tessdata or TESSDATA)
    t0 = time.time()
    try:
        out = subprocess.run(
            [TESS, tmp, "stdout", "-l", lang, "--psm", str(psm)],
            capture_output=True, env=env, timeout=180,
        )
        text = out.stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        text = ""
    finally:
        os.unlink(tmp)
    return text, (time.time() - t0) * 1000


def labelled(split=None):
    items = []
    for f in sorted(os.listdir(LABELS)):
        if not f.endswith(".txt"):
            continue
        base = f[:-4]
        img = source_image(base)
        if img:
            items.append((base, img, open(os.path.join(LABELS, f), encoding="utf-8").read()))
    if split:
        keep = set(json.load(open(split))[  "ids" ])
        items = [i for i in items if i[0] in keep]
    return items


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default="ind")
    ap.add_argument("--psm", type=int, default=3)
    ap.add_argument("--max-px", type=int, default=2600)
    ap.add_argument("--tessdata", default=None)
    ap.add_argument("--prep", default="gray")
    ap.add_argument("--ids", default=None, help="JSON file with a list of basenames")
    ap.add_argument("--out", default=None)
    a = ap.parse_args()

    ids = set(json.load(open(a.ids))) if a.ids else None
    rows = []
    for base, img, ref in labelled():
        if ids is not None and base not in ids:
            continue
        hyp, ms = run_tesseract(img, a.lang, a.psm, a.max_px, a.tessdata, a.prep)
        m = compare(ref, hyp)
        m.update(id=base, ms=round(ms), hyp_chars=len(normalize(hyp)))
        rows.append(m)
        print(f"{base:28s} CER {m['cer']*100:6.2f}%  WER {m['wer']*100:6.2f}%  "
              f"ref {m['expected_chars']:5d}  hyp {m['hyp_chars']:5d}  {m['ms']:5d}ms", flush=True)

    if rows:
        tot_e = sum(r["expected_chars"] for r in rows)
        tot_w = sum(r["expected_words"] for r in rows)
        # corpus-level: weight by reference length (standard for CER/WER)
        cer = sum(r["cer"] * r["expected_chars"] for r in rows) / tot_e
        wer = sum(r["wer"] * r["expected_words"] for r in rows) / tot_w
        macro = sum(r["cer"] for r in rows) / len(rows)
        print(f"\nn={len(rows)}  corpus CER {cer*100:.2f}%  corpus WER {wer*100:.2f}%  "
              f"macro CER {macro*100:.2f}%  chars {tot_e:,}")
        if a.out:
            json.dump({"config": vars(a), "rows": rows,
                       "corpus_cer": cer, "corpus_wer": wer, "macro_cer": macro,
                       "n": len(rows), "chars": tot_e},
                      open(a.out, "w"), indent=2)


if __name__ == "__main__":
    main()
