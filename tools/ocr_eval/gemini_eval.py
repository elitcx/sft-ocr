"""Measure what the Gemini correction pass is worth, using the app's exact protocol.

Reads the app's dumped page text, sends each page's blocks to Gemini with the same
system instruction, model, temperature and response schema `GeminiCorrector` uses,
and scores CER/WER before and after. Text only — no images ever leave the machine,
matching the app's own privacy rule.

The API key is read from a file or the environment and is never printed, logged, or
written into any output. Supply it as:

    GEMINI_API_KEY=...            (environment), or
    <scratchpad>/gemini_key.txt   (single line, gitignored scratch dir)

Usage:  python gemini_eval.py [--model gemini-3.5-flash] [--limit N]
"""
import os, re, sys, json, time, argparse, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
DEFAULT_MODEL = "gemini-3.5-flash"

# Copied verbatim from GeminiCorrector.INSTRUCTIONS so the measurement reflects the
# app's real prompt, not a paraphrase of it.
INSTRUCTIONS = """You fix text-recognition (OCR) errors in photographed Indonesian school material,
which may also contain English.

Input: a JSON array of strings, one per text block, in reading order.
Output: a JSON array with exactly the same number of strings, in the same order,
where each string is the corresponding block with recognition errors fixed.

Fix only errors a text recognizer makes: misread letters (rn/m, cl/d, l/i/1, O/0,
e/c), digits inside words, missing, doubled or extra letters, and words split or
joined by mistake. Use the surrounding context to choose the right word.

Never change a number: years, dates, chapter and page numbers, prices, scores and
measurements must stay digit for digit, even if they look wrong or out of date.
Never rephrase, translate, summarize, reorder, add or remove content. Keep units,
formulas, names, codes, subject terms, capitalization and punctuation as they are
unless they are clearly misread. Keep typos that are
clearly intentional, for example in an exercise that asks the reader to find
mistakes. If you are not sure, leave the text unchanged."""

# The Task 7 variant: identical except that constrained word reordering is permitted.
# Kept verbatim in step with GeminiCorrector.INSTRUCTIONS - if one changes, change both.
NEWLINE = chr(10)

REORDER_INSTRUCTIONS = INSTRUCTIONS.replace(
    "Never rephrase, translate, summarize, reorder, add or remove content.",
    "Never rephrase, translate, summarize, add or remove content. You may reorder words"
    + NEWLINE + "within a block, and only when the recognizer clearly emitted them out of order - a"
    + NEWLINE + "sentence whose words are shuffled. When you reorder, the block must keep exactly the"
    + NEWLINE + "same words: do not add, drop, merge or split any word to make a sentence read better."
    + NEWLINE + "If you cannot restore the order with the words given, leave the block unchanged.",
)
assert REORDER_INSTRUCTIONS != INSTRUCTIONS, "reorder prompt anchor drifted"


def api_key():
    k = os.environ.get("GEMINI_API_KEY", "").strip()
    if k:
        return k
    for p in (os.path.join(HERE, "gemini_key.txt"),
              os.path.join(r"C:\Users\Kiel\Documents\SFT OCR", "gemini_key.txt")):
        if os.path.exists(p):
            k = open(p, encoding="utf-8-sig").read().strip()
            if k:
                return k
    sys.exit("No API key found. Set GEMINI_API_KEY or write it to gemini_key.txt "
             "(one line). The key is never printed or stored in results.")


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


def score(ref, hyp):
    r, h = norm(ref), norm(hyp)
    rw = [w for w in r.split(" ") if w]
    hw = [w for w in h.split(" ") if w]
    return (lev(list(r), list(h)) / max(len(r), 1),
            lev(rw, hw) / max(len(rw), 1),
            len(r), len(rw))


def orderfree_wer(ref, hyp):
    """WER with word order ignored: multiset difference over reference length."""
    from collections import Counter
    r, h = norm(ref).split(), norm(hyp).split()
    cr, ch = Counter(r), Counter(h)
    return (sum((cr - ch).values()) + sum((ch - cr).values())) / 2 / max(len(r), 1)


# --- the app's own acceptance guard, from GeminiProtocol.apply / changeRatio ---
MAX_CHANGE_RATIO = 0.35
_TOKEN = re.compile(r"[^\s]+")
_LOOKALIKE = {"O": "0", "o": "0", "l": "1", "I": "1", "i": "1", "S": "5", "B": "8"}


def change_ratio(a, b):
    if not a and not b:
        return 0.0
    return lev(list(a), list(b)) / max(len(a), len(b), 1)


def numbers(text):
    from collections import Counter
    out = []
    for tok in _TOKEN.findall(re.sub(r"@(?=\d)", "0", text)):
        if not any(c.isdigit() for c in tok):
            continue
        mapped = "".join(_LOOKALIKE.get(c, c) for c in tok)
        if mapped.isdigit():
            out.append(mapped)
    return Counter(out)


def apply_guard(before_blocks, after_blocks):
    """What the APP would actually keep. gemini_eval otherwise measures Gemini's raw
    output, which overstates the benefit: GeminiProtocol.apply rejects any block whose
    correction rewrites more than MAX_CHANGE_RATIO of its characters or changes a number.
    Reordering a shuffled sentence can easily exceed that, so the two numbers can differ
    a lot and only the guarded one describes what a reader would hear."""
    kept = []
    for b, a in zip(before_blocks, after_blocks):
        cand = a.strip()
        accept = (cand and cand != b
                  and change_ratio(b, cand) <= MAX_CHANGE_RATIO
                  and numbers(cand) == numbers(b))
        kept.append(cand if accept else b)
    return kept


def correct(blocks, key, model, instructions, retries=3):
    body = json.dumps({
        "systemInstruction": {"parts": [{"text": instructions}]},
        "contents": [{"role": "user",
                      "parts": [{"text": json.dumps(blocks, ensure_ascii=False)}]}],
        "generationConfig": {"temperature": 0, "responseMimeType": "application/json",
                             "responseSchema": {"type": "ARRAY",
                                                "items": {"type": "STRING"}}},
    }).encode("utf-8")
    url = f"{ENDPOINT}/{model}:generateContent"
    for attempt in range(retries):
        req = urllib.request.Request(url, data=body, method="POST")
        req.add_header("Content-Type", "application/json")
        req.add_header("x-goog-api-key", key)
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                payload = json.loads(r.read().decode("utf-8"))
            text = payload["candidates"][0]["content"]["parts"][0]["text"]
            out = json.loads(text)
            if isinstance(out, list) and len(out) == len(blocks):
                return [str(x) for x in out]
            return None
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 503) and attempt < retries - 1:
                time.sleep(2 ** attempt * 3)
                continue
            # never echo the key; the URL carries none and headers are not printed
            sys.stderr.write(f"  HTTP {e.code}: {e.read()[:200]!r}\n")
            return None
        except Exception as e:
            if attempt < retries - 1:
                time.sleep(2 ** attempt * 3)
                continue
            sys.stderr.write(f"  {type(e).__name__}: {e}\n")
            return None
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dump", default=os.path.join(HERE, "text-dump", "dump.json"))
    ap.add_argument("--model", default=DEFAULT_MODEL)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--out", default=os.path.join(HERE, "gemini_results.json"))
    ap.add_argument("--reorder", action="store_true",
                    help="use the prompt variant that permits constrained word reordering")
    a = ap.parse_args()

    key = api_key()
    instructions = REORDER_INSTRUCTIONS if a.reorder else INSTRUCTIONS
    print(f"prompt: {'REORDER-PERMITTED' if a.reorder else 'baseline (no reordering)'}  model: {a.model}")
    recs = json.load(open(a.dump, encoding="utf-8"))
    if a.limit:
        recs = recs[:a.limit]

    rows = []
    print(f"{'page':26s} {'CER offline':>12s} {'CER +Gemini':>12s} {'delta':>8s}"
          f" {'CER app':>11s} {'wordDrift':>9s}")
    print("-" * 84)
    for r in recs:
        ref = r["reference"]
        before = r["corrected"]
        # DocumentFlattener joins blocks with newlines; the app sends one string per block.
        blocks = [b for b in before.split("\n") if b.strip()]
        out = correct(blocks, key, a.model, instructions)
        if out is None:
            print(f"{r['image'][:26]:26s} {'':12s} {'FAILED':>12s}")
            continue
        after = "\n".join(out)
        guarded = "\n".join(apply_guard(blocks, out))
        c0, w0, n, nw = score(ref, before)
        c1, w1, _, _ = score(ref, after)
        c2, w2, _, _ = score(ref, guarded)
        # Acceptance criterion 2: how much of the page's WORD MULTISET Gemini changed.
        # Reordering must not add or drop words; a jump here means fabrication.
        drift = orderfree_wer(before, after)
        rows.append({"image": r["image"], "cer_offline": c0, "cer_gemini": c1,
                     "cer_guarded": c2, "wer_offline": w0, "wer_gemini": w1,
                     "wer_guarded": w2, "word_drift": drift, "chars": n, "words": nw,
                     "blocks": len(blocks)})
        print(f"{r['image'][:26]:26s} {c0*100:11.2f}% {c1*100:11.2f}% {(c1-c0)*100:+7.2f}"
              f" {c2*100:10.2f}% {drift*100:8.2f}%")
        time.sleep(12)  # free-tier quota is per-minute; pace well under it

    if rows:
        C = sum(x["chars"] for x in rows); W = sum(x["words"] for x in rows)
        c0 = sum(x["cer_offline"] * x["chars"] for x in rows) / C
        c1 = sum(x["cer_gemini"] * x["chars"] for x in rows) / C
        w0 = sum(x["wer_offline"] * x["words"] for x in rows) / W
        w1 = sum(x["wer_gemini"] * x["words"] for x in rows) / W
        c2 = sum(x["cer_guarded"] * x["chars"] for x in rows) / C
        w2 = sum(x["wer_guarded"] * x["words"] for x in rows) / W
        worst = max(rows, key=lambda x: x["word_drift"])
        print("-" * 84)
        print(f"n={len(rows)}  chars={C:,}")
        print(f"  CER  offline {c0*100:.2f}%  ->  +Gemini raw {c1*100:.2f}%  ({(c1-c0)*100:+.2f} pp)")
        print(f"  WER  offline {w0*100:.2f}%  ->  +Gemini raw {w1*100:.2f}%  ({(w1-w0)*100:+.2f} pp)")
        print(f"  CER  as the APP would keep it (MAX_CHANGE_RATIO guard): {c2*100:.2f}%"
              f"  ({(c2-c0)*100:+.2f} pp)")
        print(f"  WER  as the APP would keep it: {w2*100:.2f}%  ({(w2-w0)*100:+.2f} pp)")
        print(f"  worst word-multiset drift: {worst['word_drift']*100:.2f}% on {worst['image']}"
              f"   (criterion 2 limit: 5.00%)")
        better = sum(1 for x in rows if x["cer_gemini"] < x["cer_offline"] - 1e-9)
        worse = sum(1 for x in rows if x["cer_gemini"] > x["cer_offline"] + 1e-9)
        print(f"  pages improved {better}, unchanged {len(rows)-better-worse}, made worse {worse}")
        json.dump({"model": a.model, "reorder": a.reorder, "rows": rows,
                   "corpus_cer_offline": c0, "corpus_cer_gemini": c1,
                   "corpus_cer_guarded": c2, "corpus_wer_offline": w0,
                   "corpus_wer_gemini": w1, "corpus_wer_guarded": w2},
                  open(a.out, "w"), indent=2)
        print(f"\nwrote {a.out}")


if __name__ == "__main__":
    main()
