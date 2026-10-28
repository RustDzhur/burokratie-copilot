# Document-quality stand

The stand answers one question with numbers instead of opinions: **how well does the
document pipeline recognise type, category and fields of real-looking documents?**

It is built from three parts:

| Part | File | What it does |
|---|---|---|
| Corpus generator | [`generate_corpus.py`](generate_corpus.py) | Renders 133 synthetic German/Russian documents (clean scans, bad photos, foreign objects, crops, multi-page PDFs, handwriting, stamps, translations) and writes `tests/corpus/documents/manifest.json` with the ground truth. |
| Pipeline harness | `services/api/src/test/java/de/buerokratiecopilot/evaluation/DocumentCorpusQualityIT.java` | Uploads every document through the **real** services (security scan → text extraction → Tesseract OCR → language → classification → field extraction) with a real PostgreSQL, and writes per-document JSON. |
| Report | [`report.py`](report.py) | Computes type/category accuracy, false automation, not-recognised share and field recall, then writes [`docs/quality/DOCUMENT_QUALITY.md`](../../docs/quality/DOCUMENT_QUALITY.md). |

The one-command entry point is [`run-corpus.sh`](run-corpus.sh).

## Quick start

```bash
# JDK 21, PostgreSQL and Tesseract with deu data are required (rus is optional)
python3 scripts/quality/generate_corpus.py --split all --clean
scripts/quality/run-corpus.sh
```

The script:

1. creates the isolated database `burokratie_quality` if it does not exist
   (`BC_TEST_DB_NAME`, `BC_TEST_DB_USER` override);
2. copies `services/api` to `target/quality/api-worktree` **without** the accidental
   `* 2.java` / `* 2.sql` duplicates that were present during the first measurement and
   break both javac and Flyway (the copy is not a source of truth; the source stays untouched);
3. runs `DocumentCorpusQualityIT` for `dev` and `holdout`;
4. runs the Russian experiment with `-Dcorpus.ocr.languages=deu+eng+rus`;
5. copies results to `tests/corpus/results/` and regenerates the report.

For a single split (no report):

```bash
scripts/quality/run-corpus.sh --split dev
```

For a quick smoke run (first N documents):

```bash
scripts/quality/run-corpus.sh --split dev --limit 5 --label smoke --no-russian
```

## Corpus

`tests/corpus/documents/` is generated, not hand-edited. Each document is described in
`manifest.json`:

```json
{
  "id": "dev-rechnung-occluded-hand-01",
  "file": "dev/dev-rechnung-occluded-hand-01.jpg",
  "language": "de",
  "trueType": "rechnung",
  "trueCategory": "FINANCE",
  "difficulty": "occluded",
  "condition": "hand",
  "expectedFields": { "Rechnungsnummer": "RE-2026-2883", "Bruttobetrag": "1128.89" },
  "expectedSupplier": "Stadtwerke Musterstadt GmbH",
  "expectedNumber": "RE-2026-2883",
  "expectedDate": "2026-03-15",
  "expectedAmount": "1128.89",
  "acceptableTypes": []
}
```

The generator is deterministic (`seed=20261026`); each document has its own RNG stream, so
adding a document does not move the layout of the others. Regenerate with:

```bash
python3 scripts/quality/generate_corpus.py --split all --clean
```

The corpus is deliberately difficult: the `degraded` bucket contains blur, motion blur,
darkness, overexposure, rotation, page curl, sensor noise, low resolution and JPEG
artifacts; `occluded` contains a hand, a desk, a shadow, a coffee ring, a paperclip and
other papers; `handwritten` and `stamped` add ink and seals. A few documents are
deliberately unreadable and are reported as such instead of being hidden.

## Metrics

`report.py` uses only JSON produced by a run; no number is written by hand.

| Metric | Denominator | Meaning |
|---|---|---|
| Type top-1 | German documents | current classification equals the true type or an explicitly acceptable alias |
| Category | German documents | category of the predicted type equals the true category |
| Auto-accepted | German documents | `documents.document_type_id` was filled automatically |
| False automation | auto-accepted | accepted type wrong — the number that must stay at zero |
| Not recognised | German documents | no current classification at all |
| Field recall | documents where the value exists | number / date / amount / supplier found under any extracted field; supplier uses `documents.sender` |
| Key-field recall | expected field entries | value found under its declared field key (e.g. `Rechnungsnummer`) |
| OCR confidence | pages | mean Tesseract confidence |

The holdout split is generated with other providers, other addresses and other wording. It
was **not** used to tune rules; it is the honest number. The dev split was used for tuning,
so its perfect score is a regression bar, not an estimate of real-world accuracy.

## Comparing changes

`tests/corpus/results/` keeps the evidence of the last run:

- `baseline-{dev,holdout}.json` — before the tuning;
- `rules-{dev,holdout}.json` — after the rule/field-hint migration only;
- `final-{dev,holdout}.json` — after the small extraction fixes;
- `ru-{dev,holdout}.json` — Russian documents with `deu+eng+rus`.

The report shows three stages side by side. To measure a new change, run the stand with a
new label and pass it to the report:

```bash
CORPUS_LABEL=candidate scripts/quality/run-corpus.sh
python3 scripts/quality/report.py --final-stage candidate
```

## Notes and constraints

- No AI model is involved. This measures the rule classifier and the rule field extractor.
- OCR is local Tesseract. The pilot default `BC_OCR_LANGUAGES=deu+eng` cannot read Cyrillic;
  the Russian experiment runs with `deu+eng+rus` and shows 13/13 detected and 0
  auto-accepted. If Russian mail is expected in production, add `rus` there or detect the
  script before classification.
- The test database is real PostgreSQL, not H2. The script uses a dedicated database so it
  does not collide with other integration tests that clean `burokratie_test`.
- Generated images/PDFs and result JSONs are working artifacts. Do not commit personal data;
  the corpus is fictitious by construction.
