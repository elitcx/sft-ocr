"""Build line-level ground truth from page-level transcripts.

Tesseract's LSTM trains on (line image, line text) pairs, but our labels are
whole pages. This bootstraps line GT:

  1. Run Tesseract to get line boxes + its own reading of each line.
  2. Align Tesseract's full reading to the human/AI page transcript with
     difflib over words.
  3. Project each line's word span through the alignment to get that line's
     corrected text.
  4. Keep a line only when the projection is unambiguous and the line looks
     sane, so we do not train on garbage labels.

The filter matters more than the volume: a wrong line label teaches the model
the wrong thing, and with a few hundred lines there is no averaging it out.
"""
import os, re, json, subprocess, tempfile, argparse, difflib
from PIL import Image, ImageOps

SRC = r"C:\Users\Kiel\Downloads\braille testing"
LABELS = r"C:\Users\Kiel\Documents\SFT OCR\braille-ocr\dataset\ai-transcribed"
HERE = os.path.dirname(os.path.abspath(__file__))
TESS = r"C:\Program Files\Tesseract-OCR\tesseract.exe"

MAX_PX = 2600
MIN_CHARS = 8           # too-short lines carry little signal, lots of noise
MAX_CHARS = 120
MIN_H, MAX_H = 12, 220  # line-image height sanity, in px
MAX_REL_EDIT = 0.45     # drop a line if OCR and projected GT disagree wildly


def norm(t):
    return " ".join(t.split())


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


def page_image(base):
    for ext in (".jpg", ".JPG", ".jpeg"):
        p = os.path.join(SRC, base + ext)
        if os.path.exists(p):
            return p
    return None


def tsv_lines(img, tessdata, lang="ind"):
    """Return [(text, (x0,y0,x1,y1), [words])] per detected text line."""
    fd, tmp = tempfile.mkstemp(suffix=".png"); os.close(fd)
    img.save(tmp)
    env = dict(os.environ, TESSDATA_PREFIX=tessdata)
    r = subprocess.run([TESS, tmp, "stdout", "-l", lang, "--psm", "3", "tsv"],
                       capture_output=True, env=env, timeout=300)
    os.unlink(tmp)
    rows = r.stdout.decode("utf-8", "replace").splitlines()
    if not rows:
        return []
    hdr = rows[0].split("\t")
    idx = {k: i for i, k in enumerate(hdr)}
    lines = {}
    for row in rows[1:]:
        c = row.split("\t")
        if len(c) < len(hdr):
            continue
        try:
            level = int(c[idx["level"]])
        except ValueError:
            continue
        if level != 5:
            continue
        txt = c[idx["text"]].strip()
        if not txt:
            continue
        key = (c[idx["block_num"]], c[idx["par_num"]], c[idx["line_num"]])
        l, t = int(c[idx["left"]]), int(c[idx["top"]])
        w, h = int(c[idx["width"]]), int(c[idx["height"]])
        e = lines.setdefault(key, {"words": [], "box": [l, t, l + w, t + h]})
        e["words"].append(txt)
        b = e["box"]
        b[0] = min(b[0], l); b[1] = min(b[1], t)
        b[2] = max(b[2], l + w); b[3] = max(b[3], t + h)
    out = []
    for key in sorted(lines, key=lambda k: (int(k[0]), int(k[1]), int(k[2]))):
        e = lines[key]
        out.append((" ".join(e["words"]), tuple(e["box"]), e["words"]))
    return out


def project(ocr_words, ref_words, spans):
    """Partition the reference word stream across lines.

    Anchor-span projection (min..max matched index) silently truncates a line
    whenever its first or last words were misread, which teaches the model to
    stop early. Instead each line gets a contiguous slice and the slices tile
    the reference with no gaps: boundaries sit midway between the last anchor
    of one line and the first anchor of the next. Pages whose anchors are not
    monotonic (multi-column, insets, reordered reading) are rejected whole,
    because there the tiling assumption does not hold.
    """
    sm = difflib.SequenceMatcher(a=[w.lower() for w in ocr_words],
                                 b=[w.lower() for w in ref_words], autojunk=False)
    amap = {}
    for a0, b0, n in sm.get_matching_blocks():
        for k in range(n):
            amap[a0 + k] = b0 + k

    first, last = [], []
    for (s, e) in spans:
        anchors = [amap[i] for i in range(s, e) if i in amap]
        first.append(min(anchors) if anchors else None)
        last.append(max(anchors) if anchors else None)

    seen = [x for x in first if x is not None]
    if len(seen) < 2 or any(b < a for a, b in zip(seen, seen[1:])):
        return [None] * len(spans)  # non-monotonic: reject the page

    n = len(spans)
    starts, ends = [None] * n, [None] * n
    for i in range(n):
        if first[i] is None:
            continue
        prev_end = next((last[j] for j in range(i - 1, -1, -1) if last[j] is not None), None)
        nxt_start = next((first[j] for j in range(i + 1, n) if first[j] is not None), None)
        starts[i] = first[i] if prev_end is None else max(prev_end + 1, min(first[i], prev_end + 1))
        ends[i] = last[i] + 1 if nxt_start is None else max(last[i] + 1, min(nxt_start, last[i] + 1))
        ends[i] = max(ends[i], starts[i])

    results = []
    for i, (s, e) in enumerate(spans):
        if starts[i] is None or ends[i] is None or ends[i] <= starts[i]:
            results.append(None)
            continue
        span = ends[i] - starts[i]
        if span > 3 * (e - s) + 6:  # projected far more words than the line holds
            results.append(None)
            continue
        results.append(" ".join(ref_words[starts[i]:ends[i]]))
    return results


def build(bases, outdir, tessdata, tag):
    os.makedirs(outdir, exist_ok=True)
    kept = dropped = 0
    manifest = []
    for base in bases:
        p = page_image(base)
        if not p:
            continue
        ref = norm(open(os.path.join(LABELS, base + ".txt"), encoding="utf-8").read())
        ref_words = [w for w in ref.split(" ") if w]
        im = ImageOps.exif_transpose(Image.open(p)).convert("L")
        im.thumbnail((MAX_PX, MAX_PX), Image.LANCZOS)
        try:
            lines = tsv_lines(im, tessdata)
        except subprocess.TimeoutExpired:
            continue
        if not lines:
            continue
        ocr_words, spans = [], []
        for txt, box, words in lines:
            s = len(ocr_words); ocr_words += words; spans.append((s, len(ocr_words)))
        proj = project(ocr_words, ref_words, spans)
        for (txt, box, words), gt in zip(lines, proj):
            if gt is None:
                dropped += 1; continue
            gt = norm(gt)
            x0, y0, x1, y1 = box
            h = y1 - y0
            if not (MIN_CHARS <= len(gt) <= MAX_CHARS) or not (MIN_H <= h <= MAX_H):
                dropped += 1; continue
            d = lev(txt, gt) / max(len(gt), 1)
            if d > MAX_REL_EDIT:
                dropped += 1; continue
            pad = max(4, h // 6)
            crop = im.crop((max(0, x0 - pad), max(0, y0 - pad),
                            min(im.width, x1 + pad), min(im.height, y1 + pad)))
            if crop.width < 16 or crop.height < 12:
                dropped += 1; continue
            name = f"{base}_{len(manifest):04d}"
            crop.save(os.path.join(outdir, name + ".tif"), compression="tiff_lzw")
            with open(os.path.join(outdir, name + ".gt.txt"), "w", encoding="utf-8") as f:
                f.write(gt + "\n")
            manifest.append({"name": name, "page": base, "gt": gt,
                             "ocr": txt, "rel_edit": round(d, 3),
                             "w": crop.width, "h": crop.height})
            kept += 1
        print(f"  {base}: {kept} kept / {dropped} dropped (running)", flush=True)
    json.dump(manifest, open(os.path.join(outdir, f"manifest_{tag}.json"), "w"), indent=1)
    print(f"{tag}: kept={kept} dropped={dropped}")
    return kept


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--split", required=True, choices=["train", "val", "test"])
    ap.add_argument("--outdir", required=True)
    ap.add_argument("--tessdata", default=os.path.join(HERE, "tessdata_best"))
    a = ap.parse_args()
    bases = json.load(open(os.path.join(HERE, f"split_{a.split}.json")))
    build(bases, a.outdir, a.tessdata, a.split)
