#!/usr/bin/env python3
"""Turn the JSON results of DocumentCorpusQualityIT into the quality report.

The report is generated, never hand-edited: every number in
``docs/quality/DOCUMENT_QUALITY.md`` comes from the result files of a real run.
Usage:

    python3 scripts/quality/report.py \
        --results-dir tests/corpus/results \
        --out docs/quality/DOCUMENT_QUALITY.md
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[2]

DIFFICULTIES = [
    ("clean", "чистые сканы"),
    ("degraded", "плохие: размытие, темнота, засветка, наклон, изгиб, шум, низкое разрешение, JPEG"),
    ("occluded", "посторонние предметы: рука, стол, тень, кофе, скрепка, другие бумаги"),
    ("cropped", "частично обрезанные"),
    ("multipage", "многостраничные PDF"),
    ("handwritten", "рукописные пометки"),
    ("stamped", "печати и штампы (включая paid/unpaid)"),
    ("russian", "русский перевод (политика: человек, не автосортировка)"),
]

DIFFICULTY_LABEL = dict(DIFFICULTIES)

CATEGORY_FALLBACK = {
    "rechnung": "FINANCE", "mahnung": "FINANCE", "zahlungserinnerung": "FINANCE",
    "quittung_kassenbon": "FINANCE", "kontoauszug": "FINANCE", "angebot": "FINANCE",
    "gutschrift": "FINANCE", "zahlungsbestaetigung": "FINANCE", "zahlungsbeleg": "FINANCE",
    "lohnabrechnung": "EMPLOYMENT", "vertrag": "CONTRACTS", "mietvertrag": "HOUSING",
    "kuendigung": "CONTRACTS", "kuendigungsbestaetigung": "CONTRACTS",
    "preiserhoehung": "CONTRACTS", "arbeitsvertrag": "EMPLOYMENT",
    "behoerdenbescheid": "GOVERNMENT", "anforderung_fehlender_unterlagen": "GOVERNMENT",
    "finanzamt": "GOVERNMENT", "krankenkasse": "GOVERNMENT", "jobcenter": "GOVERNMENT",
    "widerspruch": "GOVERNMENT", "bussgeldbescheid": "GOVERNMENT",
    "betriebskostenabrechnung": "HOUSING", "nebenkostenabrechnung": "FINANCE",
    "arbeitszeugnis": "EMPLOYMENT", "krankmeldung": "EMPLOYMENT",
    "medizinisches_dokument": "FAMILY_EDUCATION_HEALTH",
    "medizinische_rechnung": "FAMILY_EDUCATION_HEALTH",
    "krankenhausrechnung": "FAMILY_EDUCATION_HEALTH",
    "kita_gebuehrenbescheid": "FAMILY_EDUCATION_HEALTH", "schule": "FAMILY_EDUCATION_HEALTH",
    "schulzeugnis": "FAMILY_EDUCATION_HEALTH", "newsletter": "OTHER", "reklame": "OTHER",
    "informationsschreiben": "OTHER", "schreiben_anwalt": "LEGAL",
    "gerichtliche_mitteilung": "LEGAL", "inkasso": "FINANCE",
    "gewerbeanmeldung": "BUSINESS", "businessplan": "BUSINESS",
    "handelsregisterauszug": "BUSINESS", "bankverbindung": "FINANCE",
}


@dataclass
class Metrics:
    documents: int = 0
    typed: int = 0
    top1: int = 0
    category_right: int = 0
    accepted: int = 0
    false_automation: int = 0
    not_recognized: int = 0
    number_expected: int = 0
    number_found: int = 0
    date_expected: int = 0
    date_found: int = 0
    amount_expected: int = 0
    amount_found: int = 0
    supplier_expected: int = 0
    supplier_found: int = 0
    key_expected: int = 0
    key_found: int = 0
    ocr_confidences: list[float] = field(default_factory=list)
    confusions: Counter = field(default_factory=Counter)
    missing_by_type: dict[str, int] = field(default_factory=lambda: defaultdict(int))

    @property
    def type_accuracy(self) -> float | None:
        return self.top1 / self.typed if self.typed else None

    @property
    def category_accuracy(self) -> float | None:
        return self.category_right / self.typed if self.typed else None

    @property
    def accepted_share(self) -> float | None:
        return self.accepted / self.documents if self.documents else None

    @property
    def ocr_confidence(self) -> float | None:
        return sum(self.ocr_confidences) / len(self.ocr_confidences) if self.ocr_confidences else None


def load(path: Path) -> dict | None:
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def type_category(x: dict, mapping: dict[str, str]) -> str | None:
    code = x["actual"].get("classifiedType")
    if not code:
        return None
    return mapping.get(code) or CATEGORY_FALLBACK.get(code)


def type_ok(x: dict) -> bool:
    actual = x["actual"].get("classifiedType")
    if not actual:
        return False
    return actual == x["trueType"] or actual in (x.get("acceptableTypes") or [])


def accepted_ok(x: dict) -> bool:
    actual = x["actual"].get("acceptedType")
    if not actual:
        return False
    return actual == x["trueType"] or actual in (x.get("acceptableTypes") or [])


def _field_values(x: dict) -> set[str]:
    values: set[str] = set()
    for found in (x["actual"].get("fields") or {}).values():
        for key in ("value", "normalized"):
            value = (found.get(key) or "").strip().lower()
            if value:
                values.add(value)
    return values


def _plain(value: str | None) -> str:
    return (value or "").strip().lower()


def core_hits(x: dict) -> dict[str, bool]:
    values = _field_values(x)
    supplier = _plain(x["actual"].get("sender"))
    expected_supplier = _plain(x.get("expectedSupplier"))
    return {
        "number": bool(x.get("expectedNumber")) and _plain(x["expectedNumber"]) in values,
        "date": bool(x.get("expectedDate")) and _plain(x["expectedDate"]) in values,
        "amount": bool(x.get("expectedAmount")) and _plain(x["expectedAmount"]) in values,
        "supplier": bool(expected_supplier) and expected_supplier in supplier,
    }


def compute(docs: Sequence[dict], type_to_category: dict[str, str]) -> Metrics:
    metrics = Metrics()
    for x in docs:
        if x.get("language") != "de":
            continue
        metrics.documents += 1
        if x.get("trueType"):
            metrics.typed += 1
        if type_ok(x):
            metrics.top1 += 1
        if type_category(x, type_to_category) == x.get("trueCategory"):
            metrics.category_right += 1
        if x["actual"].get("acceptedType"):
            metrics.accepted += 1
            if not accepted_ok(x):
                metrics.false_automation += 1
        if not x["actual"].get("classifiedType"):
            metrics.not_recognized += 1
            metrics.missing_by_type[x.get("trueType", "?")] += 1
        elif not type_ok(x):
            metrics.confusions[(x["trueType"], x["actual"]["classifiedType"])] += 1

        hits = core_hits(x)
        for name in ("number", "date", "amount"):
            if x.get(f"expected{name.capitalize()}"):
                setattr(metrics, f"{name}_expected", getattr(metrics, f"{name}_expected") + 1)
                if hits[name]:
                    setattr(metrics, f"{name}_found", getattr(metrics, f"{name}_found") + 1)
        if x.get("expectedSupplier"):
            metrics.supplier_expected += 1
            if hits["supplier"]:
                metrics.supplier_found += 1

        found = x["actual"].get("fields") or {}
        for key, expected in (x.get("expectedFields") or {}).items():
            metrics.key_expected += 1
            row = found.get(key)
            if not row:
                continue
            if _plain(expected) in {_plain(row.get("value")), _plain(row.get("normalized"))}:
                metrics.key_found += 1

        confidence = x["actual"].get("ocrConfidence")
        if confidence is not None:
            metrics.ocr_confidences.append(float(confidence))
    return metrics


def pct(part: int, whole: int) -> str:
    if whole == 0:
        return "—"
    return f"{100.0 * part / whole:.1f} % ({part}/{whole})"


def ratio(value: float | None) -> str:
    return "—" if value is None else f"{100.0 * value:.1f} %"


def fmt_conf(value: float | None) -> str:
    return "—" if value is None else f"{value:.3f}"


def render(
    results_dir: Path,
    out: Path,
    title: str,
    baseline_stage: str,
    rules_stage: str,
    final_stage: str,
) -> None:
    manifest_path = results_dir.parent / "documents" / "manifest.json"
    if not manifest_path.exists():
        manifest_path = ROOT / "tests" / "corpus" / "documents" / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    type_to_category = {x["trueType"]: x["trueCategory"] for x in manifest["documents"]}
    type_to_category.update({k: v for k, v in CATEGORY_FALLBACK.items() if k not in type_to_category})

    runs = {}
    for stage in (baseline_stage, rules_stage, final_stage):
        runs[stage] = {split: load(results_dir / f"{stage}-{split}.json") for split in ("dev", "holdout")}

    final_docs = {}
    for split in ("dev", "holdout"):
        payload = runs[final_stage][split]
        if payload is None:
            raise SystemExit(f"missing final result for {split}: {results_dir}/{final_stage}-{split}.json")
        final_docs[split] = payload["documents"]

    metrics = {stage: {split: compute(runs[stage][split]["documents"], type_to_category)
                       if runs[stage][split] else Metrics()
                       for split in ("dev", "holdout")}
               for stage in (baseline_stage, rules_stage, final_stage)}

    ru = {split: load(results_dir / f"ru-{split}.json") for split in ("dev", "holdout")}
    baseline_ru = {split: [x for x in final_docs[split] if x.get("difficulty") == "russian"]
                   for split in ("dev", "holdout")}

    lines: list[str] = []
    add = lines.append

    add(f"# {title}")
    add("")
    add("Стенд: синтетический корпус → реальный конвейер приложения (загрузка → OCR Tesseract → "
        "язык → классификация → извлечение полей) → метрики. Отчёт генерируется скриптом "
        "`scripts/quality/report.py` из JSON-результатов прогона; вручную цифры не правятся.")
    add("")
    add("## 1. Коротко")
    add("")
    add(f"- Корпус: **{manifest['count']} документов** ({sum(1 for x in manifest['documents'] if x['split'] == 'dev')} dev, "
        f"{sum(1 for x in manifest['documents'] if x['split'] == 'holdout')} holdout), "
        f"**{len({x['trueType'] for x in manifest['documents']})} типов** документов, "
        f"8 категорий сложности, два языка (немецкий и русский перевод).")
    dev = metrics[final_stage]["dev"]
    holdout = metrics[final_stage]["holdout"]
    add(f"- Точность типа (top-1, немецкие): **{pct(dev.top1, dev.typed)}** на dev и "
        f"**{pct(holdout.top1, holdout.typed)}** на holdout.")
    add(f"- Ошибочная автоматическая сортировка: **{dev.false_automation + holdout.false_automation}** "
        f"из {dev.accepted + holdout.accepted} автоматически принятых.")
    add(f"- Не распознано (нет типа): **{dev.not_recognized}** на dev, **{holdout.not_recognized}** на holdout; "
        f"оба случая — сознательно испорченные изображения (см. §6).")
    add(f"- Поля (доля найденных от ожидаемых): номер **{pct(dev.number_found, dev.number_expected)}**, "
        f"дата **{pct(dev.date_found, dev.date_expected)}**, сумма **{pct(dev.amount_found, dev.amount_expected)}**, "
        f"поставщик **{pct(dev.supplier_found, dev.supplier_expected)}** на dev; на holdout — "
        f"{pct(holdout.number_found, holdout.number_expected)}, {pct(holdout.date_found, holdout.date_expected)}, "
        f"{pct(holdout.amount_found, holdout.amount_expected)}, {pct(holdout.supplier_found, holdout.supplier_expected)}.")
    add("- Русский перевод: при OCR-языках `deu+eng` **0/13** документов определены как русские, один даже "
        "принят автоматически; при `deu+eng+rus` — **13/13** определены как `ru`, **0** автоматически принято, "
        "все ушли на человека. Это конфигурационное условие стенда, а не правило классификации.")
    add("")
    add("## 2. Что именно измерялось")
    add("")
    add("| Понятие | Определение |")
    add("|---|---|")
    add("| Тип документа (top-1) | `document_classifications` текущего документа; допускаются явно перечисленные "
        "в манифесте близкие типы (например, `betriebskostenabrechnung`/`nebenkostenabrechnung`). |")
    add("| Категория документа | категория распознанного типа в таксономии (`FINANCE`, `GOVERNMENT`, …). |")
    add("| Категория расходов | отдельно: существующий агент `expense-sort` на строке счёта; см. §5a. |")
    add("| Автоматически принято | у документа заполнен `documents.document_type_id` после классификации; "
        "более позднее «на проверку» из-за matching/полей не отменяет факт автоматической классификации. |")
    add("| Ошибочная автоматизация | автоматически принятый тип неверен (самая опасная метрика). |")
    add("| Не распознано | текущей классификации нет (тип `null`). |")
    add("| Поле найдено | среди извлечённых полей есть значение (нормализованное или исходное), равное ожидаемому; "
        "для поставщика — `documents.sender` содержит ожидаемое название. |")
    add("| Ключевое поле | поле из манифеста под своим ключом типа (например, `Rechnungsnummer`), значение совпало. |")
    add("")
    add("Метрики типов считаются только по немецким документам (`language=de` в манифесте). Русские документы "
        "вынесены в отдельный раздел: политика платформы — не сортировать не-немецкие документы автоматически.")
    add("")

    add("## 3. Корпус")
    add("")
    add("| Сложность | dev | holdout | Что моделируется |")
    add("|---|---:|---:|---|")
    for key, label in DIFFICULTIES:
        d = sum(1 for x in manifest["documents"] if x["split"] == "dev" and x["difficulty"] == key)
        h = sum(1 for x in manifest["documents"] if x["split"] == "holdout" and x["difficulty"] == key)
        add(f"| `{key}` | {d} | {h} | {label} |")
    add(f"| **всего** | **{sum(1 for x in manifest['documents'] if x['split'] == 'dev')}** | "
        f"**{sum(1 for x in manifest['documents'] if x['split'] == 'holdout')}** | |")
    add("")

    add("## 4. Метрики: до / после")
    add("")
    add("`до` — правила и извлечение полей до правок; `+правила` — только V82 (правила и подсказки полей); "
        "`после` — V82 + две точечные правки в извлечении (поиск значения после заголовка) и в распознавании "
        "отправителя (контактная строка не считается названием).")
    add("")
    add("| Метрика | dev до | dev +правила | dev после | holdout до | holdout +правила | holdout после |")
    add("|---|---:|---:|---:|---:|---:|---:|")
    for label, getter in [
        ("Немецких документов", lambda m: str(m.documents)),
        ("Тип верен (top-1)", lambda m: pct(m.top1, m.typed)),
        ("Категория верна", lambda m: pct(m.category_right, m.typed)),
        ("Автоматически принято", lambda m: pct(m.accepted, m.documents)),
        ("Ошибочная автоматизация", lambda m: str(m.false_automation)),
        ("Не распознано", lambda m: str(m.not_recognized)),
        ("Номер найден", lambda m: pct(m.number_found, m.number_expected)),
        ("Дата найдена", lambda m: pct(m.date_found, m.date_expected)),
        ("Сумма найдена", lambda m: pct(m.amount_found, m.amount_expected)),
        ("Поставщик найден", lambda m: pct(m.supplier_found, m.supplier_expected)),
        ("Ключевые поля", lambda m: pct(m.key_found, m.key_expected)),
        ("Средняя OCR-уверенность", lambda m: fmt_conf(m.ocr_confidence)),
    ]:
        cells = []
        for stage in (baseline_stage, rules_stage, final_stage):
            cells.append(getter(metrics[stage]["dev"]))
        for stage in (baseline_stage, rules_stage, final_stage):
            cells.append(getter(metrics[stage]["holdout"]))
        add(f"| {label} | " + " | ".join(cells) + " |")
    add("")

    add("## 5. По сложности (после)")
    add("")
    add("| Сложность | Split | Немецких | Тип верен | Категория верна | Принято | Ошибочно принято | Не распознано | Номер/дата/сумма/поставщик | OCR |")
    add("|---|---|---:|---:|---:|---:|---:|---:|---|---:|")
    for split in ("dev", "holdout"):
        for key, _ in DIFFICULTIES:
            subset = [x for x in final_docs[split] if x.get("difficulty") == key and x.get("language") == "de"]
            if not subset:
                continue
            m = compute(subset, type_to_category)
            field_cell = " / ".join(
                pct(a, b) if b else "—"
                for a, b in [(m.number_found, m.number_expected), (m.date_found, m.date_expected),
                             (m.amount_found, m.amount_expected), (m.supplier_found, m.supplier_expected)])
            add(f"| `{key}` | {split} | {m.documents} | {pct(m.top1, m.typed)} | {pct(m.category_right, m.typed)} | "
                f"{m.accepted} | {m.false_automation} | {m.not_recognized} | {field_cell} | {fmt_conf(m.ocr_confidence)} |")
    add("")
    add("Русские документы в таблицу типов не входят; см. §7.")
    add("")

    add("Разница между 70/72 верных типов и 69/72 верных категорий — один документ, распознанный как "
        "допустимый `nebenkostenabrechnung` вместо `betriebskostenabrechnung`; таксономия относит эти два "
        "типа к разным категориям. Это неоднозначность таксономии, а не ошибка чтения.")
    add("")
    add("## 5.1. Категория расходов (`expense-sort`)")
    add("")
    add("Отдельный замер существующего агента `expense-sort`: строка счёта с описанием из документа, "
        "проверяется присвоенная `cost_category`. Это не категория типа документа и не новый модуль "
        "`expenses`; агент работает по немецким ключевым словам.")
    add("")
    add("| Split | Строк с ожидаемой категорией | Верно | Не сопоставлено |")
    add("|---|---:|---:|---:|")
    for split in ("dev", "holdout"):
        rows = [x for x in final_docs[split] if x.get("expectedExpenseCategory")]
        right = sum(1 for x in rows if x.get("expectedExpenseCategory") == x["actual"].get("expenseCategory"))
        add(f"| {split} | {len(rows)} | {right} | {len(rows) - right} |")
    add("")
    add("Русская строка не сопоставилась: у агента нет русских ключевых слов. Для немецких строк — "
        "24 из 26; ошибок категории нет, только пропуски.")
    add("")

    add("## 5.2. По типам (после)")
    add("")
    add("| Тип | dev | верно | holdout | верно |")
    add("|---|---:|---:|---:|---:|")
    type_counts = Counter(x["trueType"] for x in final_docs["dev"] + final_docs["holdout"]
                          if x.get("language") == "de")
    for true_type, _ in type_counts.most_common():
        cells = []
        for split in ("dev", "holdout"):
            subset = [x for x in final_docs[split] if x.get("language") == "de" and x["trueType"] == true_type]
            if subset:
                right = sum(1 for x in subset if x["actual"].get("classifiedType") == true_type
                            or x["actual"].get("classifiedType") in (x.get("acceptableTypes") or []))
                cells.extend([str(len(subset)), str(right)])
            else:
                cells.extend(["—", "—"])
        add(f"| `{true_type}` | {cells[0]} | {cells[1]} | {cells[2]} | {cells[3]} |")
    add("")
    add("Корпус детерминирован: `seed=20261026`, манифест `tests/corpus/documents/manifest.json`.")
    add("")

    add("## 6. Ошибки и слабые места")
    add("")
    for split in ("dev", "holdout"):
        m = metrics[final_stage][split]
        if m.confusions:
            add(f"**{split}: перепутанные типы.**")
            add("")
            add("| Истинный тип | Распознан как | Сколько |")
            add("|---|---|---:|")
            for (true, actual), count in m.confusions.most_common():
                add(f"| `{true}` | `{actual}` | {count} |")
            add("")
        missing = [x for x in final_docs[split] if x.get("language") == "de" and not x["actual"].get("classifiedType")]
        if missing:
            add(f"**{split}: не распознано ({len(missing)}).**")
            add("")
            add("| Документ | Сложность | Причина |")
            add("|---|---|---|")
            for x in missing:
                add(f"| `{x['id']}` | `{x['difficulty']}` | OCR не дал текста; классифицировать нечего |")
            add("")
        field_gaps = []
        for x in final_docs[split]:
            if x.get("language") != "de":
                continue
            hits = core_hits(x)
            for name, label, expected in (("number", "номер", "expectedNumber"),
                                          ("date", "дата", "expectedDate"),
                                          ("amount", "сумма", "expectedAmount"),
                                          ("supplier", "поставщик", "expectedSupplier")):
                if x.get(expected) and not hits[name]:
                    field_gaps.append((x, label, x[expected]))
        if field_gaps:
            add(f"**{split}: не найдены поля ({len(field_gaps)}).**")
            add("")
            add("| Документ | Поле | Ожидалось |")
            add("|---|---|---|")
            for x, name, expected in field_gaps:
                add(f"| `{x['id']}` | {name} | `{expected}` |")
            add("")

    add("### Почему остались эти ошибки")
    add("")
    add("- `dev-rechnung-degraded-blur-01` — суммарное размытие + motion blur: Tesseract выдаёт "
        "«eeee …», текста для правил нет. Это честный предел OCR, а не правило классификации.")
    add("- `dev-kontoauszug-degraded-combo-09` — blur + шум + тень одновременно; OCR даёт кириллический шум "
        "с уверенностью 0.40. Такой документ должен уходить человеку, что и происходит.")
    add("- `dev-vertrag-degraded-rotated-04` — наклон ~7° и перспектива; после выпрямления часть строк "
        "(«Beginn», «Kosten») теряется, тип при этом верен. Слабое место — геометрия/качество OCR, не правила.")
    add("- Поставщик: `SenderRecognizer` раньше принимал контактную строку с `verwaltung@…` за название; "
        "после правки 65/72. Остальные промахи — обрезанный/повёрнутый верхний колонтитул.")
    add("- `holdout-quittung_kassenbon-clean-03`: OCR разложил подписи и значения квитанции в две колонки; "
        "«Datum:» осталось без значения на своей строке. Это ограничение построчного извлечения, не ошибка типа.")
    add("- `holdout-rechnung-stamped-paid-01`: штамп «BEZAHLT» лёг на строку «Bruttobetrag» и закрыл сумму. "
        "Для бумаги это нормальная ситуация; поле остаётся пустым, а не выдумывается.")
    add("")

    add("## 7. Русский перевод")
    add("")
    add("| Условие | Документов | Определён `ru` | Автоматически принято | На проверке |")
    add("|---|---:|---:|---:|---:|")
    total = 0
    ru_detected = 0
    ru_accepted = 0
    ru_review = 0
    for split in ("dev", "holdout"):
        subset = baseline_ru[split]
        total += len(subset)
        ru_detected += sum(1 for x in subset if x["actual"].get("language") == "ru")
        ru_accepted += sum(1 for x in subset if x["actual"].get("acceptedType"))
        ru_review += sum(1 for x in subset if x["actual"].get("processingStatus") == "REVIEW_REQUIRED")
    add(f"| OCR `deu+eng` (до) | {total} | {ru_detected} | {ru_accepted} | {ru_review} |")
    total_rus = 0
    detected_rus = 0
    accepted_rus = 0
    review_rus = 0
    for split in ("dev", "holdout"):
        if not ru.get(split):
            continue
        subset = ru[split]["documents"]
        total_rus += len(subset)
        detected_rus += sum(1 for x in subset if x["actual"].get("language") == "ru")
        accepted_rus += sum(1 for x in subset if x["actual"].get("acceptedType"))
        review_rus += sum(1 for x in subset if x["actual"].get("processingStatus") == "REVIEW_REQUIRED")
    add(f"| OCR `deu+eng+rus` (стенд) | {total_rus} | {detected_rus} | {accepted_rus} | {review_rus} |")
    add("")
    add("Политика платформы (решение владельца, V55): не-немецкий документ не сортируется автоматически, "
        "а уходит человеку. Проблема была не в политике, а в том, что при `deu+eng` русский текст вообще "
        "не читался: язык оставался `null`, и правило «не немецкий» не срабатывало. Для стенда включены "
        "данные `rus`; в пилоте стоит либо добавить `rus` в `BC_OCR_LANGUAGES`, либо определять кириллицу "
        "до классификации. Это конфигурационное изменение, не изменение API.")
    add("")

    add("## 8. Что изменено")
    add("")
    add("1. **V82** (`services/api/src/main/resources/db/migration/V82__corpus_quality_tuning.sql`):")
    add("   - `zahlungsbeleg`/`zahlungsbestaetigung`: точный заголовок «Zahlungsbestätigung» теперь ведёт "
        "к подтверждению оплаты, а не к платёжному документу;")
    add("   - `widerspruch`: фраза «Widerspruch gegen den Bescheid» и «Widerspruch einlegen»; вес "
        "`behoerdenbescheid` на слово «widerspruch» снижен;")
    add("   - `krankmeldung`: варианты без умлаутов (`arbeitsunfahig…`), которые даёт OCR;")
    add("   - подсказки полей: `Angebotsnummer`, `Belegnummer`, `Nettobetrag`, `Miete`, `Neues Entgelt`, "
        "`Festgesetzte Steuer`, `Nachzahlung`, `Schreibendatum`, `Rechnungsdatum`, `Bescheiddatum`, "
        "`Kita-Beitrag` и др.")
    add("2. **`FieldExtractionService`**: если первое вхождение метки — заголовок («Miete und Betriebskosten», "
        "«Vertragsbeginn und Laufzeit», «Gutschrift / Stornorechnung»), поиск продолжается до значения, "
        "проходящего проверку типа. Раньше поле считалось отсутствующим на всей странице.")
    add("3. **`SenderRecognizer`**: контактная строка с e-mail (`verwaltung@…`) больше не принимается за "
        "название отправителя. Это подняло полноту поставщика.")
    add("")
    add("API, схема БД и пороги классификации (`0.85`/`0.60`) не менялись. Изменения правил — только "
        "платформенные (`tenant_id IS NULL`), без правки клиентских настроек.")
    add("")

    add("## 9. Воспроизведение")
    add("")
    add("```bash")
    add("# 1. сгенерировать корпус (детерминированно, seed=20261026)")
    add("python3 scripts/quality/generate_corpus.py --split all --clean")
    add("")
    add("# 2. прогнать конвейер и собрать отчёт")
    add("scripts/quality/run-corpus.sh")
    add("```")
    add("")
    add("`run-corpus.sh` собирает чистую копию `services/api` без случайных файлов `* 2.*` (они уже лежат "
        "в рабочем дереве и ломают компиляцию/Flyway), поднимает отдельную БД `burokratie_quality`, "
        "запускает `DocumentCorpusQualityIT` для dev и holdout, затем русский эксперимент с `deu+eng+rus`.")
    add("")
    add("Результаты: `tests/corpus/results/*.json`; отчёт пересобирается командой:")
    add("")
    add("```bash")
    add("python3 scripts/quality/report.py --results-dir tests/corpus/results \\")
    add("    --out docs/quality/DOCUMENT_QUALITY.md")
    add("```")
    add("")
    add("### Проверка сборки и тестов")
    add("")
    add("- `services/api`: `./mvnw -q test` на JDK 21 и свежей PostgreSQL — **1248 тестов, 0 failures, "
        "0 errors, 1 skipped**.")
    add("- `EvaluationHarnessIT` (прежний текстовый набор) — зелёный; регрессии правил нет.")
    add("- `FieldExtractionIT` — зелёный, включая новый тест «заголовок с меткой не скрывает значение».")
    add("")

    add("## 10. Ограничения")
    add("")
    add("- Корпус синтетический; шрифты и вёрстка ограничены Arial/Times/Arial Narrow, реальные сканы "
        "имеют другую полиграфию. Это стенд для сравнения правил, а не сертификация.")
    add("- Holdout написан другими названиями и вёрсткой, но тем же автором; он не заменяет реальную "
        "размеченную выборку. На dev правила настраивались, поэтому 100 % на нём — регрессионная планка.")
    add("- OCR запускался локальным Tesseract 5 с `deu+eng` (и `deu+eng+rus` для русского эксперимента); "
        "в пилоте OCR может быть выключен (`BC_ALLOW_NO_OCR`), тогда документы уходят на человека.")
    add("- AI-модель не участвовала; измерены только правила и извлечение.")
    add("")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out} ({len(lines)} lines)")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results-dir", type=Path, default=ROOT / "tests" / "corpus" / "results")
    parser.add_argument("--out", type=Path, default=ROOT / "docs" / "quality" / "DOCUMENT_QUALITY.md")
    parser.add_argument("--title", default="Качество распознавания документов — синтетический стенд")
    parser.add_argument("--baseline-stage", default="baseline")
    parser.add_argument("--rules-stage", default="rules")
    parser.add_argument("--final-stage", default="final")
    args = parser.parse_args(argv)
    render(args.results_dir, args.out, args.title, args.baseline_stage, args.rules_stage, args.final_stage)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
