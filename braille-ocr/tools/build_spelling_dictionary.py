"""Builds the bundled spelling dictionaries in ocr-mlkit/src/main/assets/spelling/.

Source: hermitdave/FrequencyWords (OpenSubtitles 2018), CC-BY-SA 4.0 -
https://github.com/hermitdave/FrequencyWords. Only plain a-z words of two or more letters
are kept (the corrector's tokenizer never looks up anything else).

- <lang>.txt: "word count" for the 50k most frequent words. These are the words the
  corrector may suggest.
- <lang>-known.txt: one word per line from the full list that is not in <lang>.txt and was
  seen at least KNOWN_MIN_COUNT times. These are only recognized as real words (so they
  are never "corrected"), never suggested: the long tail also holds names and typos.

Usage: python tools/build_spelling_dictionary.py
"""
import re
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/{0}/{0}_{1}.txt"
OUT = Path(__file__).resolve().parent.parent / "ocr-mlkit/src/main/assets/spelling"
WORD = re.compile(r"^[a-z]{2,}$")
# The English full list carries a lot of non-English subtitle noise, so it needs more
# evidence before a word counts as real.
KNOWN_MIN_COUNT = {"id": 5, "en": 20}


def fetch(lang: str, size: str) -> list[tuple[str, int]]:
    with urllib.request.urlopen(BASE.format(lang, size)) as response:
        lines = response.read().decode("utf-8").splitlines()
    rows = []
    for line in lines:
        parts = line.split(" ")
        if len(parts) == 2 and WORD.match(parts[0]) and parts[1].isdigit():
            rows.append((parts[0], int(parts[1])))
    return rows


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for lang, min_count in KNOWN_MIN_COUNT.items():
        top = fetch(lang, "50k")
        (OUT / f"{lang}.txt").write_text("".join(f"{w} {c}\n" for w, c in top), encoding="utf-8")
        listed = {w for w, _ in top}
        known = [w for w, c in fetch(lang, "full") if c >= min_count and w not in listed]
        (OUT / f"{lang}-known.txt").write_text("".join(f"{w}\n" for w in known), encoding="utf-8")
        print(f"{lang}: {len(top)} suggestible words, {len(known)} known-only words")


if __name__ == "__main__":
    main()
