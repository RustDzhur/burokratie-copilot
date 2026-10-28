#!/usr/bin/env python3
"""Generate the synthetic document corpus for the document-quality stand.

The corpus is built so that the *real* pipeline can be measured end to end:
every document is a picture or an image-only PDF, so the platform has to run
OCR (Tesseract), language detection, classification and field extraction.
The true type, category and field values are written next to the file in
``tests/corpus/documents/manifest.json``.

Why synthetic: real letters contain personal data and cannot be committed. The
templates below are invented German business letters; the names, addresses,
IBANs and tax numbers are fictitious (the IBANs carry a valid checksum so the
field extractor has something real to validate).

Usage:
    python3 scripts/quality/generate_corpus.py                # dev + holdout
    python3 scripts/quality/generate_corpus.py --split dev
    python3 scripts/quality/generate_corpus.py --clean --split all

The generator is deterministic: the same seed produces byte-identical files
(Pillow/OpenCV are deterministic for the operations used here). Every document
uses its own RNG stream derived from the seed and the document id, so adding a
document does not shift the layout of the others.
"""

from __future__ import annotations

import argparse
import json
import math
import random
import shutil
import sys
import time
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path
from typing import Callable, Iterable, Sequence

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# --------------------------------------------------------------------------------------
# Paths and constants
# --------------------------------------------------------------------------------------

ROOT = Path(__file__).resolve().parents[2]
OUT_ROOT = ROOT / "tests" / "corpus" / "documents"
MANIFEST = OUT_ROOT / "manifest.json"

PAGE_W, PAGE_H = 1654, 2339  # A4 at 200 dpi
DPI = 200
SEED = 20261026
CORPUS_VERSION = 1

FONT_DIR = Path("/System/Library/Fonts/Supplemental")
FALLBACK_FONT = FONT_DIR / "Arial.ttf"
UNICODE_FONT = FONT_DIR / "Arial Unicode.ttf"
HAND_FONT = FONT_DIR / "Bradley Hand Bold.ttf"
SECOND_HAND_FONT = Path("/System/Library/Fonts/Noteworthy.ttc")

# OCR works on the whole page; these colours keep enough contrast for Tesseract.
INK = (24, 24, 24)
GREY = (92, 92, 92)
LIGHT_GREY = (238, 238, 238)
ACCENT = (38, 56, 92)

# --------------------------------------------------------------------------------------
# Scenarios (fictitious providers)
# --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class Scenario:
    key: str
    name: str
    street: str
    zip: str
    city: str
    phone: str
    email: str
    iban: str
    bic: str
    vat: str
    tax_no: str
    contact: str
    authority: bool = False


DEV_SCENARIOS = [
    Scenario("stadtwerke", "Stadtwerke Musterstadt GmbH", "Hauptstraße 12", "12345", "Musterstadt",
             "0345 123456", "service@stadtwerke-musterstadt.de",
             "DE89370400440532013000", "COBADEFFXXX", "DE123456789", "123/456/78901", "Frau Berger"),
    Scenario("hausmeister", "Hausmeisterservice Lehmann", "Gartenweg 3", "80331", "München",
             "089 445566", "info@hausmeister-lehmann.de",
             "DE91100000000123456789", "BYLADEM1001", "DE987654321", "143/222/33445", "Herr Lehmann"),
    Scenario("nordlicht", "Nordlicht Software GmbH", "Speicherstadt 7", "20457", "Hamburg",
             "040 998877", "rechnung@nordlicht-software.de",
             "DE02120300000000202051", "BYLADEM1001", "DE555666777", "22/333/44455", "Frau Rabe"),
    Scenario("weber", "Kanzlei Weber & Partner", "Königsallee 18", "40212", "Düsseldorf",
             "0211 334455", "kanzlei@weber-partner.de",
             "DE44500105175407324931", "INGDDEFFXXX", "DE111222333", "55/666/77788", "Herr Dr. Weber"),
    Scenario("autohaus", "Autohaus Bruns GmbH", "Industriestraße 44", "28195", "Bremen",
             "0421 556677", "service@autohaus-bruns.de",
             "DE75500105175407324931", "INGDDEFFXXX", "DE333444555", "77/888/99900", "Frau Bruns"),
    Scenario("kita", "Kita Sonnenschein", "Am Anger 9", "04109", "Leipzig",
             "0341 223344", "leitung@kita-sonnenschein.de",
             "DE12500105175407324931", "INGDDEFFXXX", "DE222333444", "11/222/33344", "Frau Adler"),
    Scenario("schule", "Grundschule Am Park", "Parkallee 2", "50667", "Köln",
             "0221 778899", "sekretariat@grundschule-am-park.de",
             "DE56500105175407324931", "INGDDEFFXXX", "DE666777888", "99/888/77766", "Frau Klein"),
    Scenario("praxis", "Praxis Dr. med. Yilmaz", "Lindenstraße 31", "70173", "Stuttgart",
             "0711 223366", "praxis@dr-yilmaz.de",
             "DE84500105175407324931", "INGDDEFFXXX", "DE777888999", "66/555/44433", "Dr. Yilmaz"),
    Scenario("hausverwaltung", "Immobilienverwaltung Krüger", "Schlossstraße 5", "01067", "Dresden",
             "0351 445599", "verwaltung@immo-krueger.de",
             "DE95500105175407324931", "INGDDEFFXXX", "DE888999000", "33/444/55566", "Herr Krüger"),
    Scenario("finanzamt", "Finanzamt Musterstadt", "Steuerplatz 1", "12345", "Musterstadt",
             "0345 999000", "poststelle@finanzamt-musterstadt.de",
             "DE60500105175407324931", "INGDDEFFXXX", "DE000000000", "123/456/78901", "Herr Schulze",
             authority=True),
    Scenario("jobcenter", "Jobcenter Musterstadt", "Sozialweg 8", "12345", "Musterstadt",
             "0345 111222", "jobcenter@musterstadt.de",
             "DE70500105175407324931", "INGDDEFFXXX", "DE000000001", "123/456/78902", "Frau Otto",
             authority=True),
    Scenario("krankenkasse", "Krankenkasse Nord", "Gesundheitsallee 4", "24103", "Kiel",
             "0431 333444", "service@krankenkasse-nord.de",
             "DE80500105175407324931", "INGDDEFFXXX", "DE000000002", "123/456/78903", "Frau Petersen"),
    Scenario("arbeitgeber", "Rheinland Logistik GmbH", "Hafenstraße 20", "47119", "Duisburg",
             "0203 667788", "personal@rheinland-logistik.de",
             "DE41500105175407324931", "INGDDEFFXXX", "DE444555666", "44/555/66677", "Herr Brandt"),
    Scenario("werbung", "Werbung Frisch", "Marktplatz 6", "79098", "Freiburg",
             "0761 889900", "hallo@werbung-frisch.de",
             "DE13500105175407324931", "INGDDEFFXXX", "DE999888777", "88/777/66655", "Frau Frisch"),
    Scenario("versicherung", "Versicherung Nordstern", "Sternstraße 10", "20095", "Hamburg",
             "040 112233", "kundenservice@nordstern.de",
             "DE23500105175407324931", "INGDDEFFXXX", "DE000000003", "123/456/78904", "Herr Timm"),
    Scenario("stadt", "Stadt Musterstadt", "Rathausplatz 1", "12345", "Musterstadt",
             "0345 555666", "stadt@musterstadt.de",
             "DE33500105175407324931", "INGDDEFFXXX", "DE000000004", "123/456/78905", "Frau Sommer",
             authority=True),
    Scenario("gericht", "Amtsgericht Musterstadt", "Justizstraße 3", "12345", "Musterstadt",
             "0345 777888", "poststelle@ag-musterstadt.de",
             "DE43500105175407324931", "INGDDEFFXXX", "DE000000005", "123/456/78906", "Herr Richter",
             authority=True),
    Scenario("inkasso", "Inkasso Nord GmbH", "Forderungsweg 11", "22335", "Hamburg",
             "040 445566", "inkasso@inkasso-nord.de",
             "DE53500105175407324931", "INGDDEFFXXX", "DE123123123", "12/312/31231", "Frau Vollstreck"),
    Scenario("gewerbe", "Gewerbeamt Musterstadt", "Amtstraße 2", "12345", "Musterstadt",
             "0345 666777", "gewerbe@musterstadt.de",
             "DE63500105175407324931", "INGDDEFFXXX", "DE000000006", "123/456/78907", "Herr Gewerbe",
             authority=True),
    Scenario("maler", "Malerbetrieb Kowalski", "Handwerksgasse 14", "44135", "Dortmund",
             "0231 889977", "info@maler-kowalski.de",
             "DE73500105175407324931", "INGDDEFFXXX", "DE321321321", "32/132/13213", "Herr Kowalski"),
    Scenario("praxis2", "Zahnarztpraxis Dr. Petrova", "Zahnstraße 7", "99084", "Erfurt",
             "0361 223311", "praxis@dr-petrova.de",
             "DE83500105175407324931", "INGDDEFFXXX", "DE777666555", "77/666/55544", "Dr. Petrova"),
]

HOLDOUT_SCENARIOS = [
    Scenario("energie", "Energieversorgung Rheintal AG", "Rheinallee 40", "68159", "Mannheim",
             "0621 445566", "kundenservice@ev-rheintal.de",
             "DE26500105175407324931", "INGDDEFFXXX", "DE456456456", "45/645/64564", "Herr Ahlers"),
    Scenario("gebaeude", "Gebäudeservice Adler", "Feldweg 12", "90402", "Nürnberg",
             "0911 223344", "office@gebaeude-adler.de",
             "DE36500105175407324931", "INGDDEFFXXX", "DE654654654", "65/465/46546", "Frau Adler"),
    Scenario("cloudwerk", "Cloudwerk Digital GmbH", "Datengasse 9", "76133", "Karlsruhe",
             "0721 556677", "billing@cloudwerk.digital",
             "DE46500105175407324931", "INGDDEFFXXX", "DE789789789", "78/978/97897", "Herr Kern"),
    Scenario("sommer", "Rechtsanwälte Sommer & Krämer", "Gerichtstraße 22", "60311", "Frankfurt",
             "069 334455", "kanzlei@sommer-kraemer.de",
             "DE56500105175407324931", "INGDDEFFXXX", "DE147147147", "14/714/71471", "Frau Sommer"),
    Scenario("zweirad", "Zweiradhaus Vogt", "Radweg 5", "04103", "Leipzig",
             "0341 667788", "service@zweirad-vogt.de",
             "DE66500105175407324931", "INGDDEFFXXX", "DE258258258", "25/825/82582", "Herr Vogt"),
    Scenario("regenbogen", "Kita Regenbogen", "Wiesenweg 3", "79098", "Freiburg",
             "0761 112233", "leitung@kita-regenbogen.de",
             "DE76500105175407324931", "INGDDEFFXXX", "DE369369369", "36/936/93693", "Frau Regen"),
    Scenario("oberschule", "Oberschule am Fluss", "Flussufer 8", "01097", "Dresden",
             "0351 556699", "sekretariat@oberschule-fluss.de",
             "DE86500105175407324931", "INGDDEFFXXX", "DE741741741", "74/174/17417", "Herr Fluss"),
    Scenario("zahnarzt", "Zahnarztpraxis Dr. Petrova", "Zahnstraße 7", "99084", "Erfurt",
             "0361 223311", "praxis@dr-petrova.de",
             "DE96500105175407324931", "INGDDEFFXXX", "DE852852852", "85/285/28528", "Dr. Petrova"),
    Scenario("bergmann", "Hausverwaltung Bergmann", "Bergstraße 18", "80331", "München",
             "089 445577", "verwaltung@hausverwaltung-bergmann.de",
             "DE16500105175407324931", "INGDDEFFXXX", "DE963963963", "96/396/39639", "Herr Bergmann"),
    Scenario("finanzamt", "Finanzamt Südstadt", "Steuerufer 3", "80331", "München",
             "089 998877", "poststelle@finanzamt-suedstadt.de",
             "DE27500105175407324931", "INGDDEFFXXX", "DE000000007", "123/456/78908", "Frau Steuer",
             authority=True),
    Scenario("arbeitsagentur", "Agentur für Arbeit Rheintal", "Arbeitsweg 2", "68159", "Mannheim",
             "0621 778899", "agentur@arbeitsagentur-rheintal.de",
             "DE37500105175407324931", "INGDDEFFXXX", "DE000000008", "123/456/78909", "Herr Arbeit",
             authority=True),
    Scenario("bkk", "BKK Rheintal", "Gesundheitsplatz 6", "68159", "Mannheim",
             "0621 334455", "service@bkk-rheintal.de",
             "DE47500105175407324931", "INGDDEFFXXX", "DE000000009", "123/456/78910", "Frau Gesund"),
    Scenario("logistik", "Rheintal Logistik GmbH", "Hafenweg 12", "68159", "Mannheim",
             "0621 889900", "personal@rheintal-logistik.de",
             "DE57500105175407324931", "INGDDEFFXXX", "DE159159159", "15/915/91591", "Herr Brandt"),
    Scenario("frisch", "Werbung Frisch", "Marktplatz 6", "79098", "Freiburg",
             "0761 889900", "hallo@werbung-frisch.de",
             "DE67500105175407324931", "INGDDEFFXXX", "DE753753753", "75/375/37537", "Frau Frisch"),
    Scenario("sanchez", "Elektro Sanchez", "Stromgasse 4", "45127", "Essen",
             "0201 112244", "info@elektro-sanchez.de",
             "DE77500105175407324931", "INGDDEFFXXX", "DE951951951", "95/195/19519", "Herr Sanchez"),
    Scenario("alpenblick", "Versicherung Alpenblick", "Panoramastraße 9", "87527", "Sonthofen",
             "08321 445566", "service@alpenblick-versicherung.de",
             "DE87500105175407324931", "INGDDEFFXXX", "DE000000010", "123/456/78911", "Frau Alp"),
    Scenario("stadt", "Stadt Rheinstadt", "Rathausplatz 1", "68159", "Mannheim",
             "0621 555666", "stadt@rheinstadt.de",
             "DE97500105175407324931", "INGDDEFFXXX", "DE000000011", "123/456/78912", "Herr Sommer",
             authority=True),
    Scenario("gericht", "Amtsgericht Rheinstadt", "Justizstraße 3", "68159", "Mannheim",
             "0621 777888", "poststelle@ag-rheinstadt.de",
             "DE18500105175407324931", "INGDDEFFXXX", "DE000000012", "123/456/78913", "Herr Richter",
             authority=True),
    Scenario("inkasso", "Inkasso West GmbH", "Forderungsweg 11", "45127", "Essen",
             "0201 445566", "inkasso@inkasso-west.de",
             "DE28500105175407324931", "INGDDEFFXXX", "DE159357159", "15/935/71591", "Frau Vollstreck"),
    Scenario("gewerbe", "Gewerbeamt Rheinstadt", "Amtstraße 2", "68159", "Mannheim",
             "0621 666777", "gewerbe@rheinstadt.de",
             "DE38500105175407324931", "INGDDEFFXXX", "DE000000013", "123/456/78914", "Herr Gewerbe",
             authority=True),
    Scenario("maler", "Malerbetrieb Nowak", "Handwerksgasse 14", "45127", "Essen",
             "0201 889977", "info@maler-nowak.de",
             "DE48500105175407324931", "INGDDEFFXXX", "DE357357357", "35/735/73573", "Herr Nowak"),
]

SCENARIOS_BY_KEY = {s.key: s for s in DEV_SCENARIOS + HOLDOUT_SCENARIOS}

# --------------------------------------------------------------------------------------
# Text model
# --------------------------------------------------------------------------------------


@dataclass
class DocContent:
    blocks: list[tuple]
    fields: dict[str, str]
    supplier: str
    number: str | None = None
    doc_date: str | None = None
    amount: str | None = None
    expense_text: str | None = None
    expense_category: str | None = None
    # Extra labels that must be present for the type to be recognisable.
    keywords: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class Layout:
    regular: Path
    bold: Path
    italic: Path
    title_align: str
    brand_align: str
    margin: int
    body_size: int
    table_style: str
    accent: tuple[int, int, int] = ACCENT


LAYOUTS = [
    Layout(FONT_DIR / "Arial.ttf", FONT_DIR / "Arial Bold.ttf", FONT_DIR / "Arial Italic.ttf",
           "left", "left", 120, 28, "lines"),
    Layout(FONT_DIR / "Arial.ttf", FONT_DIR / "Arial Bold.ttf", FONT_DIR / "Arial Italic.ttf",
           "center", "center", 135, 28, "plain"),
    Layout(FONT_DIR / "Times New Roman.ttf", FONT_DIR / "Times New Roman Bold.ttf",
           FONT_DIR / "Times New Roman Italic.ttf", "left", "left", 125, 29, "lines"),
    Layout(FONT_DIR / "Arial.ttf", FONT_DIR / "Arial Bold.ttf", FONT_DIR / "Arial Italic.ttf",
           "left", "right", 110, 27, "zebra"),
    Layout(FONT_DIR / "Arial Narrow.ttf", FONT_DIR / "Arial Narrow Bold.ttf",
           FONT_DIR / "Arial Narrow Italic.ttf", "left", "left", 100, 27, "plain"),
]

RUSSIAN_LAYOUT = Layout(UNICODE_FONT, UNICODE_FONT, UNICODE_FONT,
                        "left", "left", 120, 28, "lines")

_font_cache: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}


def font(path: Path, size: int) -> ImageFont.FreeTypeFont:
    key = (str(path), size)
    if key not in _font_cache:
        try:
            _font_cache[key] = ImageFont.truetype(str(path), size)
        except OSError:
            _font_cache[key] = ImageFont.truetype(str(FALLBACK_FONT), size)
    return _font_cache[key]


def esc(value: str) -> str:
    return value.replace("\u00a0", " ")


# --------------------------------------------------------------------------------------
# Renderer
# --------------------------------------------------------------------------------------


def _text_width(draw: ImageDraw.ImageDraw, text: str, f: ImageFont.FreeTypeFont) -> float:
    return draw.textlength(esc(text), font=f)


def _wrap(draw: ImageDraw.ImageDraw, text: str, f: ImageFont.FreeTypeFont, width: float) -> list[str]:
    words = esc(text).split()
    if not words:
        return [""]
    lines: list[str] = []
    current = ""
    for word in words:
        candidate = word if not current else current + " " + word
        if _text_width(draw, candidate, f) <= width:
            current = candidate
        else:
            if current:
                lines.append(current)
            if _text_width(draw, word, f) > width:
                # Break a very long word by characters rather than overflowing.
                chunk = ""
                for char in word:
                    if _text_width(draw, chunk + char, f) > width and chunk:
                        lines.append(chunk)
                        chunk = char
                    else:
                        chunk += char
                current = chunk
            else:
                current = word
    if current:
        lines.append(current)
    return lines


def _draw(draw: ImageDraw.ImageDraw, xy: tuple[float, float], text: str, f: ImageFont.FreeTypeFont,
          fill=INK, bold: bool = False, anchor=None) -> None:
    # Arial Unicode has no bold face; a thin stroke is the closest honest approximation.
    if bold and f.path == str(UNICODE_FONT):
        draw.text(xy, esc(text), font=f, fill=fill, anchor=anchor, stroke_width=1, stroke_fill=fill)
    else:
        draw.text(xy, esc(text), font=f, fill=fill, anchor=anchor)


def _footer(page: Image.Image, page_no: int, total: int, brand: str, layout: Layout) -> None:
    draw = ImageDraw.Draw(page)
    small = font(layout.regular, 18)
    y = PAGE_H - 62
    draw.line([(layout.margin, y - 18), (PAGE_W - layout.margin, y - 18)], fill=(180, 180, 180), width=1)
    _draw(draw, (layout.margin, y), brand, small, fill=GREY)
    _draw(draw, (PAGE_W - layout.margin, y), f"Seite {page_no} von {total}", small, fill=GREY,
          anchor="ra")


def render(blocks: Sequence[tuple], layout: Layout, brand: str) -> list[Image.Image]:
    pages: list[Image.Image] = []
    page = Image.new("RGB", (PAGE_W, PAGE_H), "white")
    draw = ImageDraw.Draw(page)
    margin = layout.margin
    usable = PAGE_W - 2 * margin
    y = margin

    body = font(layout.regular, layout.body_size)
    body_bold = font(layout.bold, layout.body_size)
    small = font(layout.regular, 21)
    small_bold = font(layout.bold, 21)
    brand_font = font(layout.bold, 33 if layout.brand_align != "center" else 35)
    sub_font = font(layout.regular, 20)
    title_font = font(layout.bold, 52)
    subtitle_font = font(layout.bold, 31)

    def new_page() -> None:
        nonlocal page, draw, y
        pages.append(page)
        page = Image.new("RGB", (PAGE_W, PAGE_H), "white")
        draw = ImageDraw.Draw(page)
        y = margin
        _draw(draw, (margin, y), brand, font(layout.regular, 20), fill=GREY)
        y += 34
        draw.line([(margin, y), (PAGE_W - margin, y)], fill=(170, 170, 170), width=1)
        y += 42

    def ensure(height: float) -> None:
        nonlocal y
        if y + height > PAGE_H - margin - 40:
            new_page()

    for block in blocks:
        kind = block[0]
        if kind == "pagebreak":
            new_page()
            continue
        if kind == "space":
            y += block[1] if len(block) > 1 else 18
            continue
        if kind == "rule":
            ensure(24)
            draw.line([(margin, y + 6), (PAGE_W - margin, y + 6)], fill=(120, 120, 120), width=2)
            y += 24
            continue
        if kind in ("brand", "brand_sub"):
            text = block[1]
            x = margin
            anchor = "la"
            if layout.brand_align == "center":
                x = PAGE_W / 2
                anchor = "ma"
            elif layout.brand_align == "right":
                x = PAGE_W - margin
                anchor = "ra"
            f = brand_font if kind == "brand" else sub_font
            color = INK if kind == "brand" else GREY
            lines = _wrap(draw, text, f, usable - 10)
            for line in lines:
                _draw(draw, (x, y), line, f, fill=color, bold=(kind == "brand"), anchor=anchor)
                y += int(f.size * 1.22)
            y += 4
            continue
        if kind == "title":
            text = block[1]
            f = title_font
            ensure(int(f.size * 1.8))
            if layout.title_align == "center":
                _draw(draw, (PAGE_W / 2, y), text, f, bold=True, anchor="ma")
                y += int(f.size * 1.4)
            else:
                _draw(draw, (margin, y), text, f, bold=True)
                y += int(f.size * 1.4)
            continue
        if kind == "subtitle":
            text = block[1]
            ensure(int(subtitle_font.size * 1.6))
            _draw(draw, (margin, y), text, subtitle_font, bold=True)
            y += int(subtitle_font.size * 1.45)
            continue
        if kind in ("text", "small"):
            f = body if kind == "text" else small
            width = usable if kind == "text" else usable * 0.90
            lines = _wrap(draw, block[1], f, width)
            height = int(f.size * 1.42) * len(lines) + 6
            ensure(height)
            for line in lines:
                _draw(draw, (margin, y), line, f, fill=INK if kind == "text" else GREY)
                y += int(f.size * 1.42)
            y += 6
            continue
        if kind == "kv":
            label, value = block[1], block[2]
            f = body
            label_text = label + ":"
            label_w = _text_width(draw, label_text, body_bold)
            value_x = margin + max(label_w + 22, usable * 0.34)
            value_width = PAGE_W - margin - value_x
            lines = _wrap(draw, value, f, max(value_width, 80))
            ensure(max(36, int(f.size * 1.42) * len(lines) + 4))
            _draw(draw, (margin, y), label_text, body_bold, bold=True)
            for line in lines:
                _draw(draw, (value_x, y), line, f)
                y += int(f.size * 1.42)
            y += 3
            continue
        if kind == "list":
            f = body
            items = block[1]
            height = sum(int(f.size * 1.42) * max(1, len(_wrap(draw, item, f, usable - 28))) for item in items)
            ensure(height + 8)
            for item in items:
                _draw(draw, (margin, y), "•", f)
                for line in _wrap(draw, item, f, usable - 28):
                    _draw(draw, (margin + 28, y), line, f)
                    y += int(f.size * 1.42)
            y += 4
            continue
        if kind == "total":
            label, value = block[1], block[2]
            f = font(layout.bold, 30 if layout.body_size < 29 else 31)
            ensure(int(f.size * 1.6))
            # Label and amount stay on one baseline: a right-aligned number in a separate
            # column is often read by Tesseract as a separate line, and the line-based field
            # extractor would then see the label without its value.
            _draw(draw, (margin + usable * 0.42, y), f"{label}: {value}", f, bold=True)
            y += int(f.size * 1.55)
            continue
        if kind == "table":
            headers = block[1]
            rows = block[2]
            weights = block[3] if len(block) > 3 else None
            table_w = usable
            if weights is None:
                weights = [1.0] * len(headers)
            total_w = sum(weights)
            widths = [table_w * w / total_w for w in weights]
            f = font(layout.regular, 22 if layout.body_size < 29 else 23)
            fb = font(layout.bold, 22 if layout.body_size < 29 else 23)
            line_h = int(f.size * 1.38)
            pad = 8

            def row_height(cells: Sequence[str], bold: bool) -> int:
                ff = fb if bold else f
                lines = 1
                for cell, w in zip(cells, widths):
                    lines = max(lines, len(_wrap(draw, cell, ff, w - 2 * pad)))
                return lines * line_h + pad

            # header
            ensure(row_height(headers, True) + 8)
            x = margin
            for cell, w in zip(headers, widths):
                if layout.table_style in ("lines", "zebra"):
                    draw.rectangle([x, y, x + w, y + row_height(headers, True)], fill=(226, 230, 236))
                _draw(draw, (x + pad, y + pad // 2), cell, fb, bold=True)
                x += w
            y += row_height(headers, True)
            for index, row in enumerate(rows):
                rh = row_height(row, False)
                if y + rh > PAGE_H - margin - 40:
                    new_page()
                    x = margin
                    for cell, w in zip(headers, widths):
                        if layout.table_style in ("lines", "zebra"):
                            draw.rectangle([x, y, x + w, y + row_height(headers, True)], fill=(226, 230, 236))
                        _draw(draw, (x + pad, y + pad // 2), cell, fb, bold=True)
                        x += w
                    y += row_height(headers, True)
                if layout.table_style == "zebra" and index % 2 == 1:
                    draw.rectangle([margin, y, margin + table_w, y + rh], fill=(246, 247, 249))
                x = margin
                for cell, w in zip(row, widths):
                    yy = y + pad // 2
                    for line in _wrap(draw, cell, f, w - 2 * pad):
                        _draw(draw, (x + pad, yy), line, f)
                        yy += line_h
                    x += w
                y += rh
            # outer and column lines
            if layout.table_style in ("lines", "zebra"):
                draw.rectangle([margin, y - sum(row_height(r, False) for r in rows)
                                - row_height(headers, True), margin + table_w, y], outline=(160, 160, 160),
                               width=1)
            y += 10
            continue
        if kind == "signature":
            name = block[1] if len(block) > 1 else ""
            ensure(120)
            y += 22
            _draw(draw, (margin, y), "Mit freundlichen Grüßen", body)
            y += 62
            _draw(draw, (margin, y), name, body_bold, bold=True)
            y += 20
            continue
        if kind == "barcode":
            # A simple deterministic bar pattern; useful as a receipt/scan element.
            ensure(60)
            seed_text = block[1]
            rng = random.Random(seed_text)
            x = margin
            for _ in range(60):
                w = rng.choice([2, 3, 5, 7])
                if rng.random() < 0.55:
                    draw.rectangle([x, y, x + w, y + 44], fill=INK)
                x += w + rng.choice([2, 3, 4])
            y += 58
            continue
        raise ValueError(f"unknown block {kind}")

    pages.append(page)
    total = len(pages)
    for number, p in enumerate(pages, start=1):
        _footer(p, number, total, brand, layout)
    return pages


# --------------------------------------------------------------------------------------
# Content builders
# --------------------------------------------------------------------------------------


def d(value: date) -> str:
    return value.strftime("%d.%m.%Y")


def eur(value: float) -> str:
    return f"{value:,.2f}".replace(",", "X").replace(".", ",").replace("X", ".") + " EUR"


def base(s: Scenario, title: str, intro: list[tuple] | None = None) -> list[tuple]:
    blocks: list[tuple] = [("brand", s.name),
                           ("brand_sub", f"{s.street} · {s.zip} {s.city} · Tel. {s.phone} · {s.email}"),
                           ("rule",), ("title", title)]
    if intro:
        blocks.extend(intro)
    return blocks


def closing(s: Scenario, extra: str | None = None) -> list[tuple]:
    blocks: list[tuple] = []
    if extra:
        blocks.append(("small", extra))
    blocks.extend([("space", 8), ("text", "Mit freundlichen Grüßen"), ("space", 26),
                   ("text", s.name), ("space", 4),
                   ("small", f"{s.street}, {s.zip} {s.city} · Telefon {s.phone} · {s.email}")])
    return blocks


def pagebreak() -> tuple:
    return ("pagebreak",)


def std_fields(number: str, day: str, gross: float, supplier: str,
               number_key: str = "Rechnungsnummer", date_key: str = "Rechnungsdatum",
               amount_key: str = "Brutto") -> dict[str, str]:
    return {number_key: number, date_key: day, amount_key: f"{gross:.2f}", "Lieferant": supplier}


# ---- finance documents ---------------------------------------------------------------


def invoice(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    number = f"RE-{2026}-{rng.randint(1000, 9999)}"
    day = date(2026, rng.randint(1, 9), rng.randint(1, 28))
    due = day + timedelta(days=rng.choice([14, 21, 30]))
    net = round(rng.uniform(120, 2400), 2)
    vat = round(net * 0.19, 2)
    gross = round(net + vat, 2)
    service = rng.choice([
        ("Hotelübernachtung im Rahmen der Messe", "travel", "Hotelübernachtung"),
        ("Büromaterial und Druckerpapier", "office", "Büromaterial"),
        ("Softwarelizenz Cloudwerk Professional (12 Monate)", "it", "Softwarelizenz"),
        ("Werbeanzeige im Stadtmagazin", "marketing", "Werbeanzeige"),
        ("Stromabschlag September 2026", "utilities", "Strom"),
        ("Steuerberatung und Buchhaltung Q3/2026", "accounting", "Steuerberatung"),
        ("Reinigung des Treppenhauses", None, "Reinigung"),
        ("Lieferung von Verpackungsmaterial", "material", "Verpackungsmaterial"),
        ("Schulung Datenschutz für 3 Mitarbeitende", "personnel", "Schulung"),
        ("Beratung und Gutachten", "fees", "Beratung"),
        ("Miete für Lagerfläche Oktober 2026", "rent", "Miete"),
    ])
    text, cat, expense = service
    qty = rng.choice([1, 1, 2, 3])
    unit = round(net / qty, 2)
    lines = [("Rechnungsnummer", number), ("Rechnungsdatum", d(day)),
             ("Kundennummer", f"K-{rng.randint(10000, 99999)}")]
    if variant % 3 == 0:
        lines.append(("Leistungszeitraum", f"{d(day.replace(day=1))} bis {d(day)}"))
    blocks = base(s, "Rechnung", [("kv", k, v) for k, v in lines])
    blocks.append(("text", "Sehr geehrte Damen und Herren,"))
    blocks.append(("text", "für die nachfolgend aufgeführten Leistungen berechnen wir Ihnen:"))
    blocks.append(("table", ["Pos.", "Beschreibung", "Menge", "Einzelpreis", "Betrag"],
                   [["1", text, str(qty), eur(unit), eur(net)]], [0.7, 5.2, 1.0, 1.7, 1.7]))
    blocks.extend([
        ("space", 12),
        ("total", "Nettobetrag", eur(net)),
        ("total", "Umsatzsteuer 19 %", eur(vat)),
        ("total", "Bruttobetrag", eur(gross)),
        ("space", 18),
        ("text", f"Zahlbar bis {d(due)} auf das Konto IBAN {s.iban}, BIC {s.bic}."),
        ("text", f"Bitte geben Sie bei der Überweisung die Rechnungsnummer {number} an."),
        ("space", 10),
        ("small", f"USt-IdNr.: {s.vat} · Steuernummer: {s.tax_no}"),
    ])
    blocks.extend(closing(s))
    fields = std_fields(number, day.isoformat(), gross, s.name)
    fields.update({"Netto": f"{net:.2f}", "Umsatzsteuer": f"{vat:.2f}"})
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{gross:.2f}",
                      expense, cat, ["Rechnung", "Rechnungsnummer", "Bruttobetrag"])


def dunning(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    number = f"RE-{2026}-{rng.randint(1000, 9999)}"
    day = date(2026, rng.randint(2, 10), rng.randint(1, 28))
    due = day + timedelta(days=10)
    amount = round(rng.uniform(89, 1900), 2)
    fee = rng.choice([0.0, 5.0, 10.0])
    total = round(amount + fee, 2)
    blocks = base(s, f"{rng.choice(['1.', '2.', 'Letzte'])} Mahnung", [
        ("kv", "Rechnungsnummer", number), ("kv", "Rechnungsdatum", d(day - timedelta(days=30))),
        ("kv", "Offener Betrag", eur(total)), ("kv", "Zahlungsfrist", d(due))])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", f"unsere Rechnung {number} über {eur(amount)} ist trotz Fälligkeit noch nicht ausgeglichen."),
        ("text", f"Wir bitten Sie, den offenen Betrag bis zum {d(due)} zu zahlen."),
        ("total", "Offener Betrag", eur(total)),
        ("total", "Mahngebühr", eur(fee)),
        ("space", 10),
        ("text", "Sollte die Zahlung bereits erfolgt sein, betrachten Sie dieses Schreiben als gegenstandslos."),
        ("text", "Andernfalls behalten wir uns vor, ein Inkasso-Unternehmen einzuschalten."),
        ("small", f"Bankverbindung: IBAN {s.iban} · BIC {s.bic}"),
    ])
    blocks.extend(closing(s))
    fields = {"Rechnungsnummer": number, "OffenerBetrag": f"{total:.2f}",
              "Frist": due.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, due.isoformat(), f"{total:.2f}",
                      "Mahnung", None, ["Mahnung", "Offener Betrag", "Zahlungsfrist"])


def payment_reminder(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    number = f"RE-{2026}-{rng.randint(1000, 9999)}"
    day = date(2026, rng.randint(2, 10), rng.randint(1, 28))
    due = day + timedelta(days=7)
    amount = round(rng.uniform(45, 800), 2)
    blocks = base(s, "Zahlungserinnerung", [
        ("kv", "Rechnungsnummer", number), ("kv", "Betrag", eur(amount)),
        ("kv", "Frist", d(due)), ("kv", "Schreibendatum", d(day))])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "sicherlich haben Sie unsere Rechnung nur übersehen. Wir erlauben uns eine freundliche Erinnerung."),
        ("text", f"Bitte überweisen Sie {eur(amount)} bis zum {d(due)} auf unser Konto."),
        ("text", "Vielen Dank für Ihre Aufmerksamkeit."),
        ("small", f"IBAN {s.iban} · BIC {s.bic}"),
    ])
    blocks.extend(closing(s))
    fields = {"Rechnungsnummer": number, "Betrag": f"{amount:.2f}", "Frist": due.isoformat(),
              "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{amount:.2f}",
                      "Zahlungserinnerung", None,
                      ["Zahlungserinnerung", "freundliche Erinnerung"])


def receipt(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    total = round(rng.uniform(3, 120), 2)
    blocks = base(s, "Quittung / Kassenbon", [
        ("kv", "Belegnummer", f"Q-{rng.randint(10000, 99999)}"),
        ("kv", "Datum", d(day)), ("kv", "Kassierer", s.contact)])
    blocks.append(("table", ["Artikel", "Menge", "Preis"],
                   [["Kaffee", "1", eur(2.80)], ["Brötchen", "2", eur(3.40)],
                    ["Büromaterial", "1", eur(total - 6.20)]], [5.0, 1.0, 1.5]))
    blocks.extend([
        ("total", "Gesamtbetrag", eur(total)),
        ("total", "Barzahlung", eur(total)),
        ("space", 8),
        ("text", "Vielen Dank für Ihren Einkauf!"),
        ("small", f"MwSt. 19 % enthalten · USt-IdNr. {s.vat}"),
        ("barcode", f"{s.key}-{total}"),
    ])
    fields = {"Betrag": f"{total:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{total:.2f}",
                      "Büromaterial", "office",
                      ["Quittung", "Kassenbon", "Barzahlung", "Vielen Dank für Ihren Einkauf"])


def bank_statement(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(3, 10), rng.randint(1, 28))
    saldo = round(rng.uniform(200, 9000), 2)
    blocks = base(s, "Kontoauszug", [
        ("kv", "Kontonummer/IBAN", s.iban), ("kv", "Auszugszeitraum", f"01.{day.month:02d}.2026 bis {d(day)}"),
        ("kv", "Alter Kontostand", eur(saldo - 1200)), ("kv", "Neuer Saldo", eur(saldo))])
    blocks.append(("table", ["Buchungstag", "Verwendungszweck", "Betrag"],
                   [["02.09.2026", "Miete September", eur(-950.00)],
                    ["05.09.2026", "Gutschrift Kunde Nordlicht", eur(2400.00)],
                    ["12.09.2026", "Büromaterial", eur(-89.90)],
                    ["21.09.2026", "Stromabschlag", eur(-140.00)]], [1.5, 5.0, 1.5]))
    blocks.append(("text", "Der Kontostand entspricht dem Stand zum Auszugsdatum."))
    fields = {"Kontonummer": s.iban, "Saldo": f"{saldo:.2f}", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, f"{saldo:.2f}", None, None,
                      ["Kontoauszug", "Buchungstag", "Kontostand"])


def quote(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    valid = day + timedelta(days=30)
    number = f"AN-{2026}-{rng.randint(100, 999)}"
    net = round(rng.uniform(400, 8000), 2)
    blocks = base(s, "Angebot", [
        ("kv", "Angebotsnummer", number), ("kv", "Angebot vom", d(day)),
        ("kv", "Gültig bis", d(valid)), ("kv", "Kundennummer", f"K-{rng.randint(1000, 9999)}")])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "wir bieten Ihnen die nachfolgenden Leistungen zu den genannten Konditionen an:"),
        ("table", ["Position", "Leistung", "Betrag"],
         [["1", "Beratung und Konzeption", eur(net * 0.45)],
          ["2", "Umsetzung und Einweisung", eur(net * 0.55)]], [1.0, 5.0, 1.8]),
        ("total", "Gesamtbetrag netto", eur(net)),
        ("text", f"Das Angebot ist bis zum {d(valid)} gültig."),
        ("small", "Alle Preise zzgl. der gesetzlichen Umsatzsteuer."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": number, "Betrag": f"{net:.2f}", "Frist": valid.isoformat(),
              "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{net:.2f}",
                      "Beratung und Konzeption", "fees",
                      ["Angebot", "Angebotsnummer", "Gültig bis"])


def credit_note(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    number = f"GS-{2026}-{rng.randint(100, 999)}"
    amount = round(rng.uniform(30, 900), 2)
    blocks = base(s, "Gutschrift / Stornorechnung", [
        ("kv", "Belegnummer", number), ("kv", "Datum", d(day)),
        ("kv", "Rechnungskorrektur zu", f"RE-2026-{rng.randint(1000, 9999)}")])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", f"wir schreiben Ihnen {eur(amount)} gut. Der Betrag wird mit der nächsten Rechnung verrechnet."),
        ("total", "Gutschrift", eur(amount)),
        ("small", "Grund: Rücksendung und Rechnungskorrektur."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": number, "Betrag": f"{amount:.2f}", "Schreibendatum": day.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Gutschrift", "Stornorechnung", "Rechnungskorrektur"])


def payment_confirmation(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    number = f"RE-2026-{rng.randint(1000, 9999)}"
    amount = round(rng.uniform(40, 1200), 2)
    blocks = base(s, "Zahlungsbestätigung", [
        ("kv", "Rechnungsnummer", number), ("kv", "Betrag", eur(amount)),
        ("kv", "Datum", d(day)), ("kv", "Zahlungsart", "Überweisung")])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "vielen Dank für Ihre Zahlung. Wir bestätigen den Eingang des Betrages."),
        ("text", "Die Rechnung ist damit vollständig ausgeglichen."),
        ("small", "Diese Bestätigung wurde maschinell erstellt."),
    ])
    blocks.extend(closing(s))
    fields = {"Rechnungsnummer": number, "Betrag": f"{amount:.2f}", "Schreibendatum": day.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Zahlungsbestätigung", "Zahlung erhalten"])


def payslip(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), 1)
    gross = round(rng.uniform(2400, 6200), 2)
    net = round(gross * 0.66, 2)
    blocks = base(s, "Lohnabrechnung", [
        ("kv", "Mitarbeiter", s.contact), ("kv", "Abrechnungsmonat", f"{day.month:02d}/{day.year}"),
        ("kv", "Personalnummer", f"P-{rng.randint(1000, 9999)}")])
    blocks.extend([
        ("table", ["Bezeichnung", "Betrag"],
         [["Bruttoarbeitslohn", eur(gross)], ["Sozialversicherung", eur(-gross * 0.20)],
          ["Lohnsteuer", eur(-gross * 0.14)]], [5.0, 2.0]),
        ("total", "Nettobetrag", eur(net)),
        ("text", f"Die Auszahlung erfolgt zum Monatsende auf das Konto IBAN {s.iban}."),
        ("small", "Diese Abrechnung ist vertraulich und nur für den Empfänger bestimmt."),
    ])
    blocks.extend(closing(s))
    fields = {"Betrag": f"{net:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, f"{net:.2f}", None, None,
                      ["Lohnabrechnung", "Bruttoarbeitslohn", "Sozialversicherung"])


# ---- contracts -----------------------------------------------------------------------


def contract(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 9), rng.randint(1, 28))
    start = day + timedelta(days=14)
    monthly = round(rng.uniform(89, 1500), 2)
    blocks = base(s, "Vertrag", [
        ("text", f"zwischen {s.name}, {s.street}, {s.zip} {s.city} (nachfolgend „Auftragnehmer“)"),
        ("text", "und der Muster Handels GmbH, Marktstraße 1, 12345 Musterstadt (nachfolgend „Auftraggeber“)"),
        ("subtitle", "§ 1 Vertragsgegenstand"),
        ("text", "Der Auftragnehmer erbringt die vereinbarten Dienstleistungen im Bereich IT-Betrieb und Support."),
        ("subtitle", "§ 2 Vertragsbeginn und Laufzeit"),
        ("kv", "Vertragsbeginn", d(start)), ("kv", "Vertragslaufzeit", "24 Monate"),
        ("text", "Der Vertrag verlängert sich um jeweils zwölf Monate, wenn er nicht gekündigt wird."),
        ("subtitle", "§ 3 Vergütung"),
        ("kv", "Kosten", f"{eur(monthly)} monatlich"),
        ("text", "Die Vergütung wird monatlich zum Monatsende auf das Konto des Auftragnehmers gezahlt."),
        ("subtitle", "§ 4 Kündigung"),
        ("kv", "Kündigungsfrist", "3 Monate"),
        ("text", "Kündigungen bedürfen der Schriftform."),
    ])
    if variant % 3 == 0:
        blocks.append(pagebreak())
        blocks.extend([
            ("subtitle", "§ 5 Pflichten des Auftragnehmers"),
            ("list", ["Wartung der vereinbarten Systeme", "Dokumentation der Leistungen",
                      "Vertraulichkeit über alle Geschäftsdaten"]),
            ("subtitle", "§ 6 Anlagen"),
            ("text", "Anlage 1: Leistungsbeschreibung. Anlage 2: Preisblatt. Beide Anlagen sind Bestandteil dieses Vertrages."),
        ])
    blocks.extend(closing(s))
    fields = {"Beginn": start.isoformat(), "Laufzeit": "24", "Kosten": f"{monthly:.2f}",
              "Kuendigungsfrist": "3", "Vertragsparteien": f"{s.name}; Muster Handels GmbH",
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, start.isoformat(), f"{monthly:.2f}", None, None,
                      ["Vertrag", "Vertragsgegenstand", "Vertragsbeginn", "Vertragslaufzeit",
                       "Kündigungsfrist"])


def rent_contract(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 9), rng.randint(1, 28))
    rent = round(rng.uniform(500, 1800), 2)
    blocks = base(s, "Mietvertrag", [
        ("text", f"zwischen {s.name} (Vermieter) und Frau Sabine Mieter, Wohnungsweg 4, 12345 Musterstadt (Mieter)."),
        ("subtitle", "§ 1 Mietgegenstand"),
        ("text", "Vermietet wird die Wohnung im 2. Obergeschoss, Lindenstraße 8, 12345 Musterstadt, 78 m², 3 Zimmer."),
        ("subtitle", "§ 2 Miete und Betriebskosten"),
        ("kv", "Miete", f"{eur(rent)} monatlich"),
        ("kv", "Nebenkosten", f"{eur(round(rent * 0.18, 2))} monatlich"),
        ("subtitle", "§ 3 Beginn und Kündigung"),
        ("kv", "Beginn", d(day)), ("kv", "Kündigungsfrist", "3 Monate"),
        ("text", "Die Miete ist bis zum dritten Werktag eines Monats im Voraus zu zahlen."),
        ("small", f"Bankverbindung: IBAN {s.iban} · BIC {s.bic}"),
    ])
    blocks.extend(closing(s))
    fields = {"Beginn": day.isoformat(), "Kosten": f"{rent:.2f}", "Kuendigungsfrist": "3",
              "Vertragsparteien": f"{s.name}; Sabine Mieter", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, f"{rent:.2f}", None, None,
                      ["Mietvertrag", "Vermieter", "Mieter", "Wohnung"])


def termination(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    number = f"V-{rng.randint(1000, 9999)}"
    blocks = base(s, "Kündigung", [
        ("kv", "Vertragsnummer", number), ("kv", "Datum", d(day)),
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", f"hiermit kündigen wir den Vertrag {number} zum nächstmöglichen Zeitpunkt."),
        ("text", "Bitte bestätigen Sie uns die Beendigung des Vertrages sowie das Datum schriftlich."),
        ("text", "Die Kündigung erfolgt fristgerecht unter Einhaltung der vereinbarten Kündigungsfrist."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": number, "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), None, None, None,
                      ["Kündigung", "hiermit kündigen wir", "zum nächstmöglichen Zeitpunkt"])


def termination_confirmation(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    number = f"V-{rng.randint(1000, 9999)}"
    end = day + timedelta(days=90)
    blocks = base(s, "Bestätigung Ihrer Kündigung", [
        ("kv", "Vertragsnummer", number), ("kv", "Datum", d(day)),
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "wir bestätigen hiermit den Eingang Ihrer Kündigung und die Beendigung des Vertrages."),
        ("text", f"Der Vertrag endet am {d(end)}."),
        ("text", "Bitte beachten Sie, dass eventuelle Restforderungen weiterhin fällig bleiben."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": number, "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), None, None, None,
                      ["Bestätigung Ihrer Kündigung", "Kündigung bestätigen"])


def price_increase(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    effective = date(2027, 1, 1)
    old = round(rng.uniform(29, 89), 2)
    new = round(old * 1.08, 2)
    blocks = base(s, "Preisanpassung", [
        ("kv", "Kundennummer", f"K-{rng.randint(10000, 99999)}"), ("kv", "Datum", d(day)),
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "zum 01.01.2027 erhöht sich das monatliche Entgelt für Ihren Tarif."),
        ("kv", "Bisheriges Entgelt", f"{eur(old)} monatlich"),
        ("kv", "Neues Entgelt", f"{eur(new)} monatlich"),
        ("text", "Die Preisanpassung erfolgt aufgrund gestiegener Kosten für Energie und Betrieb."),
        ("text", "Sie haben das Recht, den Vertrag bis zum Wirksamwerden der Änderung außerordentlich zu kündigen."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"K-{rng.randint(10000, 99999)}", "Schreibendatum": day.isoformat(),
              "Betrag": f"{new:.2f}", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{new:.2f}", None, None,
                      ["Preisanpassung", "erhöht sich das monatliche Entgelt", "außerordentlich zu kündigen"])


def employment_contract(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    start = day + timedelta(days=30)
    salary = round(rng.uniform(2800, 6200), 2)
    blocks = base(s, "Arbeitsvertrag", [
        ("text", f"zwischen {s.name} (Arbeitgeber) und Herrn Jonas Beispiel (Arbeitnehmer)."),
        ("subtitle", "§ 1 Tätigkeit"),
        ("text", "Der Arbeitnehmer wird als Fachinformatiker für Systemintegration eingestellt."),
        ("subtitle", "§ 2 Beginn und Probezeit"),
        ("kv", "Beginn", d(start)), ("kv", "Probezeit", "6 Monate"),
        ("subtitle", "§ 3 Vergütung"),
        ("kv", "Kosten", f"{eur(salary)} brutto monatlich"),
        ("text", "Die Vergütung wird monatlich zum Monatsende ausgezahlt."),
        ("subtitle", "§ 4 Arbeitszeit und Urlaub"),
        ("text", "Die wöchentliche Arbeitszeit beträgt 40 Stunden. Der Urlaubsanspruch beträgt 30 Tage."),
    ])
    blocks.extend(closing(s))
    fields = {"Beginn": start.isoformat(), "Kosten": f"{salary:.2f}", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, f"{salary:.2f}", None, None,
                      ["Arbeitsvertrag", "Arbeitgeber", "Probezeit"])


# ---- authority letters -----------------------------------------------------------------


def official_decision(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    deadline = day + timedelta(days=30)
    file_no = f"AZ-{rng.randint(100000, 999999)}"
    blocks = base(s, "Bescheid", [
        ("kv", "Aktenzeichen", file_no), ("kv", "Bescheiddatum", d(day)),
        ("kv", "Adressat", "Frau Erika Muster, Musterweg 7, 12345 Musterstadt"),
        ("kv", "Behörde", s.name)])
    blocks.extend([
        ("text", "Sehr geehrte Frau Muster,"),
        ("text", "auf Ihren Antrag vom 12.08.2026 ergeht folgender Bescheid:"),
        ("text", "Der Antrag wird bewilligt. Die Leistung wird ab dem 01.09.2026 für zwölf Monate gewährt."),
        ("kv", "Frist", f"bis zum {d(deadline)}"),
        ("text", "Bitte reichen Sie die fehlenden Nachweise bis zur genannten Frist nach."),
        ("subtitle", "Rechtsbehelfsbelehrung"),
        ("text", "Gegen diesen Bescheid können Sie innerhalb eines Monats nach Bekanntgabe Widerspruch einlegen."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": file_no, "Bescheiddatum": day.isoformat(), "Frist": deadline.isoformat(),
              "Behoerde": s.name, "Adressat": "Frau Erika Muster", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, file_no, day.isoformat(), None, None, None,
                      ["Bescheid", "Aktenzeichen", "Rechtsbehelfsbelehrung", "Widerspruch"])


def missing_documents(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    deadline = day + timedelta(days=21)
    file_no = f"AZ-{rng.randint(100000, 999999)}"
    blocks = base(s, "Anforderung fehlender Unterlagen", [
        ("kv", "Aktenzeichen", file_no), ("kv", "Datum", d(day)),
        ("kv", "Frist", f"bis zum {d(deadline)}")])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "für die Bearbeitung Ihres Antrags fehlen uns noch folgende Unterlagen:"),
        ("list", ["Einkommensnachweis der letzten drei Monate",
                  "Mietvertrag oder aktuelle Mieterbescheinigung",
                  "Nachweis über die Krankenversicherung"]),
        ("text", "Bitte reichen Sie die Unterlagen bis zur oben genannten Frist ein."),
        ("text", "Andernfalls können wir über den Antrag nicht entscheiden."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": file_no, "Schreibendatum": day.isoformat(), "Frist": deadline.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, file_no, day.isoformat(), None, None, None,
                      ["fehlen uns noch", "Unterlagen", "frist"])


def tax_office(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(120, 4200), 2)
    file_no = f"ST-{rng.randint(100000, 999999)}"
    blocks = base(s, "Steuerbescheid", [
        ("kv", "Steuernummer", s.tax_no), ("kv", "Aktenzeichen", file_no),
        ("kv", "Bescheiddatum", d(day)), ("kv", "Behörde", s.name)])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "für das Kalenderjahr 2025 wird die Einkommensteuer wie folgt festgesetzt:"),
        ("total", "Festgesetzte Steuer", eur(amount)),
        ("text", f"Der Betrag ist bis zum {d(day + timedelta(days=30))} zu zahlen."),
        ("text", "Gegen diesen Bescheid können Sie innerhalb eines Monats Einspruch einlegen."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": file_no, "Bescheiddatum": day.isoformat(), "Behoerde": s.name,
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, file_no, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Steuerbescheid", "Steuernummer", "Finanzamt", "Einspruch"])


def health_insurance(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(80, 900), 2)
    blocks = base(s, "Beitragsbescheid", [
        ("kv", "Versichertennummer", f"V-{rng.randint(100000, 999999)}"),
        ("kv", "Datum", d(day)), ("kv", "Krankenkasse", s.name)])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "die Beiträge zur Kranken- und Pflegeversicherung werden ab 01.10.2026 neu festgesetzt."),
        ("kv", "Monatlicher Betrag", f"{eur(amount)} monatlich"),
        ("text", "Der Beitrag wird monatlich zum Ersten eines Monats fällig."),
        ("text", "Bitte prüfen Sie die Angaben und melden Sie Änderungen unverzüglich."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"V-{rng.randint(100000, 999999)}", "Schreibendatum": day.isoformat(),
              "Betrag": f"{amount:.2f}", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Krankenkasse", "Beitragsbescheid", "Versichertennummer"])


def jobcenter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(400, 1600), 2)
    blocks = base(s, "Bürgergeld – Bescheid", [
        ("kv", "Aktenzeichen", f"JC-{rng.randint(100000, 999999)}"),
        ("kv", "Datum", d(day)), ("kv", "Bedarfsgemeinschaft", "Muster, Erika und Angehörige")])
    blocks.extend([
        ("text", "Sehr geehrte Frau Muster,"),
        ("text", "Ihr Antrag auf Bürgergeld nach dem SGB II wird teilweise bewilligt."),
        ("kv", "Monatlicher Betrag", eur(amount)),
        ("text", "Die Leistung wird für den Zeitraum 01.10.2026 bis 30.09.2027 gewährt."),
        ("text", "Bitte teilen Sie Änderungen Ihrer Einkommensverhältnisse unverzüglich mit."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"JC-{rng.randint(100000, 999999)}", "Schreibendatum": day.isoformat(),
              "Betrag": f"{amount:.2f}", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Jobcenter", "Bürgergeld", "Bedarfsgemeinschaft", "SGB II"])


def objection(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Widerspruch gegen den Bescheid", [
        ("kv", "Aktenzeichen", f"AZ-{rng.randint(100000, 999999)}"), ("kv", "Datum", d(day))])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "hiermit lege ich Widerspruch gegen den Bescheid vom 12.09.2026 ein."),
        ("text", "Ich bitte um Überprüfung der Berechnung und um eine nachvollziehbare Begründung."),
        ("text", "Die Frist zur Einlegung des Widerspruchs ist gewahrt."),
        ("text", "Bitte bestätigen Sie den Eingang dieses Schreibens."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"AZ-{rng.randint(100000, 999999)}", "Schreibendatum": day.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Widerspruch gegen", "lege ich Widerspruch ein", "Überprüfung"])


def fine_notice(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(60, 350), 2)
    blocks = base(s, "Bußgeldbescheid", [
        ("kv", "Aktenzeichen", f"BG-{rng.randint(100000, 999999)}"),
        ("kv", "Datum", d(day)), ("kv", "Betroffener", "Herr Max Muster")])
    blocks.extend([
        ("text", "Sehr geehrter Herr Muster,"),
        ("text", "Ihnen wird eine Verkehrsordnungswidrigkeit zur Last gelegt."),
        ("text", "Sie haben die zulässige Höchstgeschwindigkeit um 21 km/h überschritten."),
        ("total", "Betrag", eur(amount)),
        ("text", "Sie können innerhalb von zwei Wochen Einspruch gegen diesen Bußgeldbescheid einlegen."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"BG-{rng.randint(100000, 999999)}", "Schreibendatum": day.isoformat(),
              "Betrag": f"{amount:.2f}", "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Bußgeldbescheid", "Verkehrsordnungswidrigkeit", "Einspruch innerhalb von zwei Wochen"])


def operating_costs(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    total = round(rng.uniform(600, 3600), 2)
    blocks = base(s, "Betriebskostenabrechnung", [
        ("kv", "Abrechnungszeitraum", "01.01.2025 bis 31.12.2025"),
        ("kv", "Wohnung", "Lindenstraße 8, 2. OG, 78 m²"),
        ("kv", "Datum", d(day))])
    blocks.append(("table", ["Kostenart", "Gesamt", "Ihr Anteil"],
                   [["Heizung und Warmwasser", eur(total * 0.42), eur(total * 0.42)],
                    ["Müllentsorgung", eur(total * 0.08), eur(total * 0.08)],
                    ["Versicherung", eur(total * 0.10), eur(total * 0.10)],
                    ["Hausmeister", eur(total * 0.15), eur(total * 0.15)],
                    ["Sonstiges", eur(total * 0.25), eur(total * 0.25)],
                    ], [4.0, 2.0, 2.0]))
    blocks.extend([
        ("total", "Nachzahlung", eur(total)),
        ("text", f"Bitte überweisen Sie den Betrag bis zum {d(day + timedelta(days=30))} auf das Konto IBAN {s.iban}."),
        ("small", "Die Abrechnung ist nach § 556 BGB umlagefähig."),
    ])
    blocks.extend(closing(s))
    fields = {"Saldo": f"{total:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{total:.2f}", None, None,
                      ["Betriebskostenabrechnung", "Abrechnungszeitraum"])


def service_charge(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    total = round(rng.uniform(300, 1500), 2)
    blocks = base(s, "Nebenkostenabrechnung", [
        ("kv", "Zeitraum", "01.01.2025 bis 31.12.2025"),
        ("kv", "Datum", d(day))])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "die Nebenkostenabrechnung für Ihre Wohnung schließt mit einer Nachzahlung ab."),
        ("text", "Folgende Kosten sind umlagefähig und wurden berücksichtigt:"),
        ("list", ["Heizkosten", "Wasser und Abwasser", "Müllgebühren", "Hausreinigung"]),
        ("total", "Nachzahlung", eur(total)),
        ("text", f"Die Zahlung ist bis zum {d(day + timedelta(days=30))} fällig."),
    ])
    blocks.extend(closing(s))
    fields = {"Saldo": f"{total:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{total:.2f}", None, None,
                      ["Nebenkostenabrechnung", "umlagefähig", "Nachzahlung"])


# ---- employment / health / school -----------------------------------------------------


def reference_letter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Arbeitszeugnis", [
        ("kv", "Datum", d(day)),
        ("kv", "Mitarbeiter", s.contact),
        ("kv", "Beschäftigungszeitraum", "01.03.2022 bis 30.09.2026")])
    blocks.extend([
        ("text", "Herr Max Muster war vom 01.03.2022 bis zum 30.09.2026 als Fachinformatiker in unserem Unternehmen tätig."),
        ("text", "Er zeigte stets sehr gute Leistungen und hat seine Aufgaben zu unserer vollsten Zufriedenheit erledigt."),
        ("text", "Sein Verhalten gegenüber Vorgesetzten und Kollegen war einwandfrei."),
        ("text", "Wir bedauern sein Ausscheiden und wünschen ihm für die Zukunft weiterhin viel Erfolg."),
    ])
    blocks.extend(closing(s))
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Arbeitszeugnis", "vollsten Zufriedenheit"])


def sick_note(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    until = day + timedelta(days=rng.randint(3, 14))
    blocks = base(s, "Arbeitsunfähigkeitsbescheinigung", [
        ("kv", "Patient", "Herr Max Muster"),
        ("kv", "Krankenkasse", s.name),
        ("kv", "Ausgestellt am", d(day)),
        ("kv", "Arbeitsunfähig bis", d(until))])
    blocks.extend([
        ("text", "Der Patient ist aufgrund einer Erkrankung arbeitsunfähig."),
        ("text", "Die Krankmeldung wird für den Arbeitgeber und die Krankenkasse ausgestellt."),
        ("small", "Diagnose: Akute Infektion der oberen Atemwege."),
    ])
    blocks.extend(closing(s))
    fields = {"Schreibendatum": day.isoformat(), "Frist": until.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Arbeitsunfähigkeitsbescheinigung", "Krankmeldung", "arbeitsunfähig"])


def medical_invoice(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(28, 420), 2)
    number = f"MR-{rng.randint(1000, 9999)}"
    blocks = base(s, "Arztrechnung", [
        ("kv", "Rechnungsnummer", number), ("kv", "Rechnungsdatum", d(day)),
        ("kv", "Behandlung am", d(day - timedelta(days=5))),
        ("kv", "Patient", "Frau Erika Muster")])
    blocks.extend([
        ("text", "Sehr geehrte Frau Muster,"),
        ("text", "für die Behandlung berechnen wir Ihnen nach der Gebührenordnung für Ärzte:"),
        ("table", ["Leistung", "Betrag"],
         [["Beratung und Untersuchung", eur(amount * 0.55)],
          ["Laborleistungen", eur(amount * 0.45)]], [5.0, 2.0]),
        ("total", "Gesamtbetrag", eur(amount)),
        ("text", f"Zahlbar bis {d(day + timedelta(days=14))} auf das Konto IBAN {s.iban}."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": number, "Betrag": f"{amount:.2f}", "Schreibendatum": day.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{amount:.2f}",
                      "Behandlung", None,
                      ["Arztrechnung", "Gebührenordnung für Ärzte", "Behandlung am"])


def hospital_invoice(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(1200, 9500), 2)
    blocks = base(s, "Krankenhausrechnung", [
        ("kv", "Fallnummer", f"F-{rng.randint(10000, 99999)}"),
        ("kv", "Aufnahme", d(day - timedelta(days=6))), ("kv", "Entlassung", d(day))])
    blocks.extend([
        ("table", ["Leistung", "Betrag"],
         [["Fallpauschale DRG G47B", eur(amount * 0.82)],
          ["Wahlleistung Unterkunft", eur(amount * 0.18)]], [5.0, 2.0]),
        ("total", "Gesamtbetrag", eur(amount)),
        ("text", "Die stationäre Behandlung wurde von der Krankenkasse vorab genehmigt."),
    ])
    blocks.extend(closing(s))
    fields = {"Betrag": f"{amount:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, f"{amount:.2f}", None, None,
                      ["Krankenhausrechnung", "Fallpauschale", "stationäre Behandlung"])


def kita_fee(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(80, 420), 2)
    blocks = base(s, "Kita-Gebührenbescheid", [
        ("kv", "Kind", "Mia Muster"), ("kv", "Kita-Beitrag", f"{eur(amount)} monatlich"),
        ("kv", "Datum", d(day)), ("kv", "Zeitraum", "01.09.2026 bis 31.08.2027")])
    blocks.extend([
        ("text", "Sehr geehrte Familie Muster,"),
        ("text", "für den Besuch der Kindertagesstätte wird ab September 2026 ein Beitrag erhoben."),
        ("text", "Der Beitrag wird monatlich zum Ersten eines Monats fällig."),
        ("text", "Bei Einkommensänderungen bitten wir um unverzügliche Mitteilung."),
    ])
    blocks.extend(closing(s))
    fields = {"Betrag": f"{amount:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Kita-Gebühr", "Kita-Beitrag", "Kindertagesstätte"])


def school_letter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    event = day + timedelta(days=14)
    blocks = base(s, "Elternbrief", [
        ("kv", "Datum", d(day)), ("kv", "Schuljahr", "2026/2027"),
        ("kv", "Klassenlehrer", s.contact)])
    blocks.extend([
        ("text", "Liebe Eltern,"),
        ("text", "wir laden Sie herzlich zum Elternabend ein."),
        ("kv", "Termin", f"{d(event)}, 18:00 Uhr"),
        ("text", "Themen sind die Schulfahrt, die Lernentwicklung und die Schulkonferenz."),
        ("text", "Bitte geben Sie bis zum 12.10.2026 die Rückmeldung an die Schulleitung zurück."),
        ("small", "Grundschule Am Park · Schulleitung"),
    ])
    blocks.extend(closing(s))
    fields = {"Schreibendatum": day.isoformat(), "Frist": event.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Elternbrief", "Elternabend", "Schulleitung", "Grundschule"])


def school_certificate(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Halbjahreszeugnis", [
        ("kv", "Schuljahr", "2026/2027"), ("kv", "Klasse", "8b"),
        ("kv", "Datum", d(day))])
    blocks.extend([
        ("text", "Schulbescheinigung und Halbjahreszeugnis für Max Muster."),
        ("table", ["Fach", "Note"],
         [["Deutsch", "2"], ["Mathematik", "1"], ["Englisch", "2"], ["Sport", "1"]], [4.0, 1.5]),
        ("text", "Die Versetzung ist gesichert. Der Schüler zeigt ein engagiertes Arbeitsverhalten."),
        ("small", "Klassenlehrer: " + s.contact),
    ])
    blocks.extend(closing(s))
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Halbjahreszeugnis", "Schulbescheinigung", "Versetzung"])


# ---- other / legal / business ----------------------------------------------------------


def newsletter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Newsletter", [
        ("kv", "Ausgabe", f"Nr. {rng.randint(10, 99)}"), ("kv", "Datum", d(day)),
        ("text", "Im Browser ansehen | Newsletter abbestellen"),
        ("subtitle", "Was gibt es Neues?"),
        ("text", "Wir informieren Sie über aktuelle Entwicklungen, neue Produkte und Veranstaltungen."),
        ("list", ["Neue Funktionen im Kundenportal", "Webinar am 12.11.2026",
                  "Tipps für die digitale Buchhaltung"]),
        ("text", "Wenn Sie diesen Newsletter nicht mehr erhalten möchten, können Sie ihn jederzeit abbestellen."),
    ])
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Newsletter", "Newsletter abbestellen", "Im Browser ansehen", "abbestellen"])


def advertisement(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Sommerschlussverkauf", [
        ("text", f"Nur bis zum {d(day + timedelta(days=10))}:"),
        ("title", "- 30 % Rabatt auf alles"),
        ("text", "Jetzt sichern und sparen! Gutschein sichern und beim nächsten Einkauf einlösen."),
        ("small", "Anzeige · " + s.name),
    ])
    fields = {"Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, None, None, None,
                      ["Sommerschlussverkauf", "Rabatt auf", "Jetzt sichern", "Gutschein sichern"])


def information_letter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Information", [
        ("kv", "Datum", d(day)),
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "hiermit informieren wir Sie über eine Änderung unseres Serviceangebots."),
        ("text", "Ab dem 01.11.2026 erreichen Sie uns montags bis freitags von 8 bis 18 Uhr."),
        ("text", "Zur Information erhalten Sie diese Mitteilung an alle Kundinnen und Kunden."),
    ])
    blocks.extend(closing(s))
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["informieren wir Sie", "zur Information"])


def lawyer_letter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Anwaltliches Schreiben", [
        ("kv", "Unser Zeichen", f"W-{rng.randint(1000, 9999)}"), ("kv", "Datum", d(day)),
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "wir vertreten die Interessen unserer Mandantin Frau Erika Muster."),
        ("text", "Wir fordern Sie auf, die offene Forderung bis zum 30.11.2026 auszugleichen."),
        ("text", "Andernfalls werden wir gerichtliche Schritte einleiten."),
        ("small", "Rechtsanwälte Weber & Partner · Kanzlei für Zivilrecht"),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"W-{rng.randint(1000, 9999)}", "Schreibendatum": day.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Rechtsanwälte", "anwaltliche Vertretung", "Kanzlei"])


def court_letter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Ladung zum Termin", [
        ("kv", "Aktenzeichen", f"{rng.randint(1, 9)} C {rng.randint(100, 999)}/{rng.randint(20, 26)}"),
        ("kv", "Datum", d(day)),
        ("text", "In der Rechtssache Muster ./. Beispiel wird Termin zur mündlichen Verhandlung bestimmt."),
        ("kv", "Termin", f"{d(day + timedelta(days=30))}, 10:00 Uhr, Saal 204"),
        ("text", "Es ergeht die Ladung zum Termin. Das Erscheinen ist erforderlich."),
        ("small", "Zustellungsurkunde liegt bei."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"{rng.randint(1, 9)} C {rng.randint(100, 999)}/{rng.randint(20, 26)}",
              "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Amtsgericht", "Ladung zum Termin", "Zustellungsurkunde"])


def collection_letter(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(120, 2900), 2)
    blocks = base(s, "Inkasso-Schreiben", [
        ("kv", "Aktenzeichen", f"I-{rng.randint(10000, 99999)}"),
        ("kv", "Gläubiger", "Stadtwerke Musterstadt GmbH"),
        ("kv", "Forderung", eur(amount)),
        ("kv", "Datum", d(day))])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "die Forderung wurde an uns abgetreten. Wir fordern Sie zur Zahlung auf."),
        ("total", "Gesamtforderung", eur(amount)),
        ("text", f"Zahlungsfrist: bis zum {d(day + timedelta(days=14))}."),
        ("text", "Nach Ablauf der Frist werden wir weitere Maßnahmen einleiten."),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"I-{rng.randint(10000, 99999)}", "Betrag": f"{amount:.2f}",
              "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Inkasso", "Forderung", "abgetreten"])


def business_registration(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Gewerbeanmeldung", [
        ("kv", "Aktenzeichen", f"G-{rng.randint(100000, 999999)}"),
        ("kv", "Datum", d(day)),
        ("kv", "Gewerbetreibende", "Erika Muster, Musterweg 7, 12345 Musterstadt")])
    blocks.extend([
        ("text", "Sehr geehrte Damen und Herren,"),
        ("text", "hiermit melde ich mein Gewerbe an. Die Tätigkeit wird ab dem 01.11.2026 ausgeübt."),
        ("kv", "Tätigkeit", "Beratung und IT-Dienstleistungen"),
        ("kv", "Betriebsstätte", "Musterweg 7, 12345 Musterstadt"),
        ("text", "Die erforderlichen Unterlagen sind beigefügt."),
        ("small", "Gewerbeamt Musterstadt · § 14 GewO"),
    ])
    blocks.extend(closing(s))
    fields = {"Aktenzeichen": f"G-{rng.randint(100000, 999999)}", "Schreibendatum": day.isoformat(),
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Gewerbeanmeldung", "Gewerbe", "14 GewO", "Gewerbeamt"])


def business_plan(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    blocks = base(s, "Businessplan 2027", [
        ("subtitle", "1. Executive Summary"),
        ("text", "Wir entwickeln eine digitale Plattform für kleine Handwerksbetriebe."),
        ("subtitle", "2. Marktanalyse"),
        ("text", "Der Zielmarkt umfasst 120.000 Betriebe in Deutschland. Die Nachfrage wächst jährlich um 8 %."),
        ("subtitle", "3. Geschäftsidee und Umsatzplanung"),
        ("text", "Die Plattform wird im Abonnement verkauft. Die Umsatzplanung rechnet mit 2.400 Kunden in drei Jahren."),
        ("subtitle", "4. Finanzplan und Kapitalbedarf"),
        ("text", "Der Kapitalbedarf beträgt 250.000 EUR. Die Liquiditätsplanung ist als Anlage beigefügt."),
        ("subtitle", "5. Rentabilitätsvorschau"),
        ("text", "Die Rentabilitätsvorschau zeigt einen positiven Deckungsbeitrag ab dem zweiten Jahr."),
    ])
    blocks.extend(closing(s))
    fields = {"Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, None, None, None, None,
                      ["Businessplan", "Executive Summary", "Marktanalyse", "Finanzplan",
                       "Kapitalbedarf", "Rentabilitätsvorschau", "Liquiditätsplan"])


def commercial_register(s: Scenario, rng: random.Random, variant: int, lang: str = "de") -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Handelsregisterauszug", [
        ("kv", "Registergericht", "Amtsgericht Musterstadt"),
        ("kv", "Registernummer", f"HRB {rng.randint(10000, 99999)}"),
        ("kv", "Datum", d(day))])
    blocks.extend([
        ("text", "Firma: Muster Handels GmbH, Sitz: Musterstadt."),
        ("text", "Geschäftsführer: Herr Max Muster, geboren 1980, wohnhaft in Musterstadt."),
        ("text", "Stammkapital: 25.000 EUR. Der Gesellschaftsvertrag ist hinterlegt."),
        ("small", "Der Auszug ist maschinell erstellt und ohne Unterschrift gültig."),
    ])
    blocks.extend(closing(s))
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Handelsregisterauszug", "Handelsregister", "HRB", "Geschäftsführer"])


# ---- Russian translations --------------------------------------------------------------

RU_HEADER = ("Уважаемые дамы и господа,")
RU_CLOSING = ("С уважением,")


def ru_invoice(s: Scenario, rng: random.Random) -> DocContent:
    number = f"RE-2026-{rng.randint(1000, 9999)}"
    day = date(2026, rng.randint(1, 9), rng.randint(1, 28))
    due = day + timedelta(days=14)
    net = round(rng.uniform(100, 1800), 2)
    vat = round(net * 0.19, 2)
    gross = round(net + vat, 2)
    blocks = base(s, "Счёт", [
        ("kv", "Номер счёта", number), ("kv", "Дата счёта", d(day)),
        ("text", "Уважаемые дамы и господа,"),
        ("text", "Направляем Вам счёт за оказанные услуги:"),
        ("table", ["Позиция", "Описание", "Сумма"],
         [["1", "Консультационные услуги", eur(net)]], [1.0, 5.0, 1.8]),
        ("total", "Сумма без НДС", eur(net)),
        ("total", "НДС 19 %", eur(vat)),
        ("total", "Итого к оплате", eur(gross)),
        ("text", f"Оплата до {d(due)} на счёт IBAN {s.iban}."),
        ("text", "Просим указать номер счёта при оплате."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Rechnungsnummer": number, "Rechnungsdatum": day.isoformat(), "Brutto": f"{gross:.2f}",
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), f"{gross:.2f}",
                      "Консультационные услуги", "fees", ["Счёт", "Номер счёта"])


def ru_reminder(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(2, 10), rng.randint(1, 28))
    amount = round(rng.uniform(50, 900), 2)
    due = day + timedelta(days=7)
    blocks = base(s, "Напоминание об оплате", [
        ("kv", "Номер счёта", f"RE-2026-{rng.randint(1000, 9999)}"),
        ("kv", "Сумма", eur(amount)), ("kv", "Срок оплаты", d(due)), ("kv", "Дата", d(day))])
    blocks.extend([
        ("text", "Уважаемые дамы и господа,"),
        ("text", "Напоминаем Вам о неоплаченном счёте."),
        ("text", f"Просим оплатить сумму до {d(due)}."),
        ("text", "Если оплата уже произведена, просьба не учитывать данное письмо."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Rechnungsnummer": f"RE-2026-{rng.randint(1000, 9999)}", "Betrag": f"{amount:.2f}",
              "Frist": due.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Напоминание об оплате"])


def ru_contract(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 9), rng.randint(1, 28))
    start = day + timedelta(days=14)
    monthly = round(rng.uniform(200, 1200), 2)
    blocks = base(s, "Договор", [
        ("text", f"между {s.name} (Исполнитель) и ООО «Пример» (Заказчик)."),
        ("subtitle", "1. Предмет договора"),
        ("text", "Исполнитель оказывает услуги по технической поддержке информационных систем."),
        ("subtitle", "2. Срок действия"),
        ("kv", "Начало", d(start)), ("kv", "Срок", "24 месяца"),
        ("kv", "Стоимость", f"{eur(monthly)} в месяц"),
        ("kv", "Срок уведомления", "3 месяца"),
        ("text", "Договор может быть расторгнут в письменной форме."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Beginn": start.isoformat(), "Kosten": f"{monthly:.2f}", "Kuendigungsfrist": "3",
              "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{monthly:.2f}", None, None,
                      ["Договор"])


def ru_termination(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    number = f"V-{rng.randint(1000, 9999)}"
    blocks = base(s, "Расторжение договора", [
        ("kv", "Номер договора", number), ("kv", "Дата", d(day)),
        ("text", "Уважаемые дамы и господа,"),
        ("text", f"Настоящим расторгаем договор {number} в ближайший возможный срок."),
        ("text", "Просим подтвердить прекращение договора в письменной форме."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Aktenzeichen": number, "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, number, day.isoformat(), None, None, None,
                      ["Расторжение договора"])


def ru_official(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    deadline = day + timedelta(days=30)
    file_no = f"AZ-{rng.randint(100000, 999999)}"
    blocks = base(s, "Решение ведомства", [
        ("kv", "Номер дела", file_no), ("kv", "Дата решения", d(day)),
        ("kv", "Ведомство", s.name)])
    blocks.extend([
        ("text", "Уважаемые дамы и господа,"),
        ("text", "По Вашему заявлению принято следующее решение."),
        ("text", "Заявление удовлетворено. Пособие назначается с 01.09.2026 на двенадцать месяцев."),
        ("kv", "Срок", f"до {d(deadline)}"),
        ("text", "Просим предоставить недостающие документы в указанный срок."),
        ("text", "Решение может быть обжаловано в течение месяца."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Aktenzeichen": file_no, "Bescheiddatum": day.isoformat(), "Frist": deadline.isoformat(),
              "Behoerde": s.name, "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, file_no, day.isoformat(), None, None, None,
                      ["Решение ведомства", "Номер дела"])


def ru_receipt(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    total = round(rng.uniform(3, 90), 2)
    blocks = base(s, "Кассовый чек", [
        ("kv", "Номер чека", f"Q-{rng.randint(10000, 99999)}"), ("kv", "Дата", d(day))])
    blocks.extend([
        ("table", ["Товар", "Количество", "Цена"],
         [["Кофе", "1", eur(2.80)], ["Булочка", "2", eur(3.40)],
          ["Канцтовары", "1", eur(total - 6.20)]], [5.0, 1.0, 1.5]),
        ("total", "Итого", eur(total)),
        ("total", "Оплата наличными", eur(total)),
        ("text", "Спасибо за покупку!"),
        ("small", "НДС 19 % включён."),
    ])
    fields = {"Betrag": f"{total:.2f}", "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{total:.2f}", None, None,
                      ["Кассовый чек"])


def ru_medical(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    amount = round(rng.uniform(30, 300), 2)
    blocks = base(s, "Медицинский счёт", [
        ("kv", "Номер счёта", f"MR-{rng.randint(1000, 9999)}"),
        ("kv", "Дата", d(day)), ("kv", "Пациент", "Госпожа Эрика Мустер")])
    blocks.extend([
        ("text", "Уважаемые дамы и господа,"),
        ("text", "Направляем счёт за медицинские услуги."),
        ("table", ["Услуга", "Сумма"],
         [["Консультация и осмотр", eur(amount * 0.6)], ["Лабораторные анализы", eur(amount * 0.4)]],
         [5.0, 2.0]),
        ("total", "Итого к оплате", eur(amount)),
        ("text", f"Оплата до {d(day + timedelta(days=14))}."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Aktenzeichen": f"MR-{rng.randint(1000, 9999)}", "Betrag": f"{amount:.2f}",
              "Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), f"{amount:.2f}", None, None,
                      ["Медицинский счёт"])


def ru_school(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Письмо родителям", [
        ("kv", "Дата", d(day)), ("kv", "Учебный год", "2026/2027")])
    blocks.extend([
        ("text", "Уважаемые родители,"),
        ("text", "Приглашаем Вас на родительское собрание."),
        ("kv", "Дата собрания", d(day + timedelta(days=14))),
        ("text", "Темы: школьная поездка, успеваемость и школьная конференция."),
        ("text", "Просим сообщить о Вашем участии до 12.10.2026."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Письмо родителям"])


def ru_employment_reference(s: Scenario, rng: random.Random) -> DocContent:
    day = date(2026, rng.randint(1, 10), rng.randint(1, 28))
    blocks = base(s, "Трудовая характеристика", [
        ("kv", "Дата", d(day)), ("kv", "Сотрудник", "Господин Макс Мустер")])
    blocks.extend([
        ("text", "Господин Макс Мустер работал в нашей компании с 01.03.2022 по 30.09.2026."),
        ("text", "Он всегда показывал очень хорошие результаты и выполнял свои задачи к полному удовлетворению руководства."),
        ("text", "Мы сожалеем о его уходе и желаем успехов в будущем."),
    ])
    blocks.extend([("space", 8), ("text", RU_CLOSING), ("space", 26), ("text", s.name)])
    fields = {"Schreibendatum": day.isoformat(), "Lieferant": s.name}
    return DocContent(blocks, fields, s.name, None, day.isoformat(), None, None, None,
                      ["Трудовая характеристика"])


BUILDERS: dict[str, Callable[[Scenario, random.Random, int, str], DocContent]] = {
    "rechnung": invoice,
    "mahnung": dunning,
    "zahlungserinnerung": payment_reminder,
    "quittung_kassenbon": receipt,
    "kontoauszug": bank_statement,
    "angebot": quote,
    "gutschrift": credit_note,
    "zahlungsbestaetigung": payment_confirmation,
    "lohnabrechnung": payslip,
    "vertrag": contract,
    "mietvertrag": rent_contract,
    "kuendigung": termination,
    "kuendigungsbestaetigung": termination_confirmation,
    "preiserhoehung": price_increase,
    "arbeitsvertrag": employment_contract,
    "behoerdenbescheid": official_decision,
    "anforderung_fehlender_unterlagen": missing_documents,
    "finanzamt": tax_office,
    "krankenkasse": health_insurance,
    "jobcenter": jobcenter,
    "widerspruch": objection,
    "bussgeldbescheid": fine_notice,
    "betriebskostenabrechnung": operating_costs,
    "nebenkostenabrechnung": service_charge,
    "arbeitszeugnis": reference_letter,
    "krankmeldung": sick_note,
    "medizinische_rechnung": medical_invoice,
    "krankenhausrechnung": hospital_invoice,
    "kita_gebuehrenbescheid": kita_fee,
    "schule": school_letter,
    "schulzeugnis": school_certificate,
    "newsletter": newsletter,
    "reklame": advertisement,
    "informationsschreiben": information_letter,
    "schreiben_anwalt": lawyer_letter,
    "gerichtliche_mitteilung": court_letter,
    "inkasso": collection_letter,
    "gewerbeanmeldung": business_registration,
    "businessplan": business_plan,
    "handelsregisterauszug": commercial_register,
}

CATEGORY = {
    "rechnung": "FINANCE", "mahnung": "FINANCE", "zahlungserinnerung": "FINANCE",
    "quittung_kassenbon": "FINANCE", "kontoauszug": "FINANCE", "angebot": "FINANCE",
    "gutschrift": "FINANCE", "zahlungsbestaetigung": "FINANCE", "lohnabrechnung": "EMPLOYMENT",
    "vertrag": "CONTRACTS", "mietvertrag": "HOUSING", "kuendigung": "CONTRACTS",
    "kuendigungsbestaetigung": "CONTRACTS", "preiserhoehung": "CONTRACTS",
    "arbeitsvertrag": "EMPLOYMENT", "behoerdenbescheid": "GOVERNMENT",
    "anforderung_fehlender_unterlagen": "GOVERNMENT", "finanzamt": "GOVERNMENT",
    "krankenkasse": "GOVERNMENT", "jobcenter": "GOVERNMENT", "widerspruch": "GOVERNMENT",
    "bussgeldbescheid": "GOVERNMENT", "betriebskostenabrechnung": "HOUSING",
    "nebenkostenabrechnung": "FINANCE", "arbeitszeugnis": "EMPLOYMENT",
    "krankmeldung": "EMPLOYMENT", "medizinische_rechnung": "FAMILY_EDUCATION_HEALTH",
    "krankenhausrechnung": "FAMILY_EDUCATION_HEALTH", "kita_gebuehrenbescheid": "FAMILY_EDUCATION_HEALTH",
    "schule": "FAMILY_EDUCATION_HEALTH", "schulzeugnis": "FAMILY_EDUCATION_HEALTH",
    "newsletter": "OTHER", "reklame": "OTHER", "informationsschreiben": "OTHER",
    "schreiben_anwalt": "LEGAL", "gerichtliche_mitteilung": "LEGAL", "inkasso": "FINANCE",
    "gewerbeanmeldung": "BUSINESS", "businessplan": "BUSINESS", "handelsregisterauszug": "BUSINESS",
}

ACCEPTABLE = {
    # Two names of the same statement are both defensible; the owner's taxonomy has both.
    "betriebskostenabrechnung": ["nebenkostenabrechnung"],
    "nebenkostenabrechnung": ["betriebskostenabrechnung"],
    "krankenkasse": ["behoerdenbescheid"],
    "finanzamt": ["behoerdenbescheid"],
}

RU_BUILDERS: dict[str, Callable[[Scenario, random.Random], DocContent]] = {
    "rechnung": ru_invoice,
    "zahlungserinnerung": ru_reminder,
    "vertrag": ru_contract,
    "kuendigung": ru_termination,
    "behoerdenbescheid": ru_official,
    "quittung_kassenbon": ru_receipt,
    "medizinische_rechnung": ru_medical,
    "schule": ru_school,
    "arbeitszeugnis": ru_employment_reference,
}

# The base type list per split. Dev is broad; holdout uses other providers and a
# deliberately smaller slice, so that adding a rule for a dev document cannot be
# mistaken for generalisation.
DEV_TYPES = [
    "rechnung", "mahnung", "zahlungserinnerung", "quittung_kassenbon", "kontoauszug",
    "angebot", "gutschrift", "zahlungsbestaetigung", "lohnabrechnung", "vertrag",
    "mietvertrag", "kuendigung", "kuendigungsbestaetigung", "preiserhoehung",
    "arbeitsvertrag", "behoerdenbescheid", "anforderung_fehlender_unterlagen",
    "finanzamt", "krankenkasse", "jobcenter", "widerspruch", "bussgeldbescheid",
    "betriebskostenabrechnung", "arbeitszeugnis", "krankmeldung", "medizinische_rechnung",
    "kita_gebuehrenbescheid", "schule", "newsletter", "reklame", "schreiben_anwalt",
    "inkasso", "gewerbeanmeldung", "businessplan", "handelsregisterauszug",
]

HOLDOUT_TYPES = [
    "rechnung", "mahnung", "quittung_kassenbon", "kontoauszug", "angebot", "vertrag",
    "mietvertrag", "kuendigung", "behoerdenbescheid", "anforderung_fehlender_unterlagen",
    "finanzamt", "krankenkasse", "medizinische_rechnung", "kita_gebuehrenbescheid",
    "schule", "newsletter", "schreiben_anwalt", "inkasso", "gewerbeanmeldung", "businessplan",
]

# Which document gets which stress condition. The mapping is explicit so that the
# report can talk about the same document in every run.
DEV_STRESS = {
    "degraded": [("rechnung", "blur"), ("mahnung", "dark"), ("angebot", "overexposed"),
                 ("vertrag", "rotated"), ("behoerdenbescheid", "curved"),
                 ("quittung_kassenbon", "noise"), ("medizinische_rechnung", "low_res"),
                 ("businessplan", "jpeg"), ("kontoauszug", "combo"), ("kuendigung", "rotated")],
    "occluded": [("rechnung", "hand"), ("mahnung", "table"), ("mietvertrag", "shadow"),
                 ("jobcenter", "coffee"), ("arbeitszeugnis", "paperclip"),
                 ("gewerbeanmeldung", "other_papers"), ("schule", "mixed"), ("inkasso", "hand")],
    "cropped": ["rechnung", "angebot", "behoerdenbescheid", "krankmeldung", "handelsregisterauszug"],
    "multipage": ["vertrag", "mietvertrag", "businessplan", "betriebskostenabrechnung"],
    "handwritten": ["rechnung", "mahnung", "behoerdenbescheid", "mietvertrag", "kita_gebuehrenbescheid"],
    "stamped": ["rechnung", "quittung_kassenbon", "zahlungsbestaetigung", "medizinische_rechnung",
                "mahnung"],
    "russian": ["rechnung", "zahlungserinnerung", "vertrag", "kuendigung", "behoerdenbescheid",
                "quittung_kassenbon", "medizinische_rechnung", "schule"],
}

HOLDOUT_STRESS = {
    "degraded": [("rechnung", "blur"), ("mahnung", "dark"), ("angebot", "overexposed"),
                 ("vertrag", "curved"), ("behoerdenbescheid", "rotated"),
                 ("kontoauszug", "low_res"), ("businessplan", "jpeg"), ("quittung_kassenbon", "noise")],
    "occluded": [("rechnung", "hand"), ("mietvertrag", "shadow"), ("inkasso", "coffee"),
                 ("gewerbeanmeldung", "other_papers"), ("schule", "mixed")],
    "cropped": ["rechnung", "angebot", "behoerdenbescheid", "schreiben_anwalt"],
    "multipage": ["vertrag", "businessplan", "mietvertrag"],
    "handwritten": ["rechnung", "mahnung", "behoerdenbescheid", "kita_gebuehrenbescheid"],
    "stamped": ["rechnung", "quittung_kassenbon", "zahlungsbestaetigung", "medizinische_rechnung"],
    "russian": ["rechnung", "vertrag", "behoerdenbescheid", "medizinische_rechnung",
                "arbeitszeugnis"],
}

# --------------------------------------------------------------------------------------
# Photo and damage simulation
# --------------------------------------------------------------------------------------


def _noise(rng: random.Random, sigma: float, shape: tuple[int, ...]) -> np.ndarray:
    """Normal noise drawn from a NumPy generator seeded by the document's RNG.

    ``random.Random`` has no ``normal``; using a fresh NumPy generator per call keeps
    the sequence deterministic without sharing a global generator between documents.
    """
    return np.random.default_rng(rng.getrandbits(64)).normal(0, sigma, shape)


def _to_cv(image: Image.Image) -> np.ndarray:
    return cv2.cvtColor(np.array(image), cv2.COLOR_RGB2BGR)


def _to_pil(array: np.ndarray) -> Image.Image:
    return Image.fromarray(cv2.cvtColor(array, cv2.COLOR_BGR2RGB))


def _wood_background(rng: random.Random, w: int, h: int) -> Image.Image:
    base = np.zeros((h, w, 3), dtype=np.uint8)
    tone = rng.choice([(150, 118, 86), (120, 100, 84), (170, 145, 112), (96, 82, 70)])
    base[:, :] = tone
    noise = _noise(rng, 10, (h, w, 3)).astype(np.int16)
    base = np.clip(base.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    for _ in range(rng.randint(8, 18)):
        y = rng.randint(0, h - 1)
        colour = tuple(int(max(0, min(255, c + rng.randint(-25, 25)))) for c in tone)
        cv2.line(base, (0, y), (w, y), colour, rng.randint(2, 7))
    texture = (_noise(rng, 6, (h, w, 1))).astype(np.int16)
    base = np.clip(base.astype(np.int16) + texture, 0, 255).astype(np.uint8)
    return Image.fromarray(cv2.cvtColor(base, cv2.COLOR_BGR2RGB))


def _perspective_page(page: Image.Image, rng: random.Random, max_offset: int) -> tuple[Image.Image, int, int, int, int]:
    w, h = page.size
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    off = lambda: rng.randint(-max_offset, max_offset)
    dst = np.float32([[off(), off()], [w + off(), off()],
                      [w + off(), h + off()], [off(), h + off()]])
    matrix = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(_to_cv(page), matrix, (w + 2 * max_offset, h + 2 * max_offset),
                                 borderValue=(255, 255, 255))
    return _to_pil(warped), max_offset, max_offset, w, h


def _scene(page: Image.Image, rng: random.Random, objects: Sequence[str]) -> Image.Image:
    """Put the page on a desk, with perspective and the requested foreign objects."""
    scale = rng.uniform(0.84, 0.92)
    pw, ph = int(page.width * scale), int(page.height * scale)
    page = page.resize((pw, ph), Image.Resampling.LANCZOS)
    max_offset = rng.randint(8, 26)
    warped, ox, oy, _, _ = _perspective_page(page, rng, max_offset)
    canvas_w = int(warped.width * rng.uniform(1.08, 1.18))
    canvas_h = int(warped.height * rng.uniform(1.08, 1.16))
    canvas = _wood_background(rng, canvas_w, canvas_h)

    # other papers behind the document
    if "other_papers" in objects:
        for _ in range(rng.randint(1, 2)):
            other = Image.new("RGB", (pw, ph), (245, 243, 238))
            od = ImageDraw.Draw(other)
            for line in range(0, ph, 60):
                od.rectangle([60, line + 60, pw - 60, line + 62], fill=(210, 210, 205))
            other = other.rotate(rng.uniform(-8, 8), expand=True, fillcolor=(250, 250, 245))
            pos = (rng.randint(0, max(1, canvas_w - other.width)),
                   rng.randint(0, max(1, canvas_h - other.height)))
            shadow = Image.new("RGBA", other.size, (0, 0, 0, 70))
            canvas.paste(shadow, (pos[0] + 8, pos[1] + 8), shadow)
            canvas.paste(other, pos)

    # drop shadow of the page
    shadow = Image.new("RGBA", (warped.width + 60, warped.height + 60), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rectangle([30, 30, 30 + warped.width, 30 + warped.height], fill=(0, 0, 0, 95))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    px = rng.randint(max_offset + 10, max(max_offset + 11, canvas_w - warped.width - 30))
    py = rng.randint(max_offset + 10, max(max_offset + 11, canvas_h - warped.height - 30))
    canvas.paste(shadow, (px - 30, py - 30), shadow)
    canvas.paste(warped, (px, py))

    # page shadow gradient over the sheet, as a lamp from the upper left often makes
    if "shadow" in objects or rng.random() < 0.35:
        overlay = Image.new("L", canvas.size, 0)
        od = ImageDraw.Draw(overlay)
        for i in range(40):
            alpha = int(70 * (i / 40) ** 1.6)
            od.rectangle([canvas_w - i * 18, 0, canvas_w, canvas_h], fill=alpha)
        shade = Image.new("RGB", canvas.size, (0, 0, 0))
        canvas = Image.composite(shade, canvas, overlay.point(lambda v: min(255, v)))

    overlay = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)

    if "hand" in objects:
        skin = (203, 160, 132, 255)
        dark = (164, 121, 96, 120)
        # palm at the right edge; fingers reach a little over the page margin
        palm_x = canvas_w - rng.randint(120, 250)
        palm_y = rng.randint(int(canvas_h * 0.45), int(canvas_h * 0.75))
        od.ellipse([palm_x, palm_y, palm_x + 360, palm_y + 430], fill=skin)
        for i in range(4):
            fx = palm_x - 120 + i * 58
            fy = palm_y - 120 + i * 10
            od.rounded_rectangle([fx, fy, fx + 52, fy + 250], radius=26, fill=skin)
        od.ellipse([palm_x - 40, palm_y + 40, palm_x + 120, palm_y + 220], fill=dark)

    if "coffee" in objects:
        cx = rng.choice([rng.randint(160, 420), rng.randint(canvas_w - 500, canvas_w - 220)])
        cy = rng.choice([rng.randint(150, 380), rng.randint(canvas_h - 520, canvas_h - 260)])
        for _ in range(2):
            r = rng.randint(90, 150)
            od.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(112, 72, 40, 105), width=rng.randint(8, 18))
            od.ellipse([cx - r - 8, cy - r - 8, cx + r + 8, cy + r + 8], outline=(140, 95, 55, 55), width=3)
        for _ in range(18):
            dx, dy = rng.randint(-160, 180), rng.randint(-140, 170)
            rr = rng.randint(2, 9)
            od.ellipse([cx + dx, cy + dy, cx + dx + rr, cy + dy + rr], fill=(120, 77, 42, rng.randint(50, 120)))

    if "paperclip" in objects:
        cx = rng.choice([rng.randint(120, 300), rng.randint(canvas_w - 300, canvas_w - 120)])
        cy = rng.randint(120, 320)
        for offset in (0, 12):
            od.arc([cx + offset, cy + offset, cx + 150 + offset, cy + 260 + offset],
                   start=200, end=20, fill=(170, 170, 175, 230), width=9)
            od.arc([cx + 35 + offset, cy + 35 + offset, cx + 115 + offset, cy + 225 + offset],
                   start=200, end=20, fill=(210, 210, 215, 220), width=7)

    canvas = Image.alpha_composite(canvas.convert("RGBA"), overlay).convert("RGB")
    return canvas


def _rotate_scene(page: Image.Image, rng: random.Random, angle: float) -> Image.Image:
    canvas = _wood_background(rng, int(page.width * 1.25), int(page.height * 1.22))
    rotated = page.rotate(angle, resample=Image.Resampling.BICUBIC, expand=True,
                          fillcolor=(255, 255, 255))
    shadow = Image.new("RGBA", (rotated.width + 50, rotated.height + 50), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    sd.rectangle([25, 25, 25 + rotated.width, 25 + rotated.height], fill=(0, 0, 0, 80))
    shadow = shadow.filter(ImageFilter.GaussianBlur(14))
    x = (canvas.width - rotated.width) // 2
    y = (canvas.height - rotated.height) // 2
    canvas.paste(shadow, (x - 25, y - 25), shadow)
    canvas.paste(rotated, (x, y))
    return canvas


def _curve(page: Image.Image, rng: random.Random) -> Image.Image:
    h, w = page.height, page.width
    canvas_w, canvas_h = int(w * 1.18), int(h * 1.16)
    canvas = _wood_background(rng, canvas_w, canvas_h)
    amplitude = rng.uniform(12, 34)
    phase = rng.uniform(0, math.pi)
    src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    off = lambda: rng.randint(-16, 16)
    dst = np.float32([[off(), off()], [w + off(), off()], [w + off(), h + off()], [off(), h + off()]])
    matrix = cv2.getPerspectiveTransform(src, dst)
    warped = cv2.warpPerspective(_to_cv(page), matrix, (w + 40, h + 40), borderValue=(255, 255, 255))
    xmap = np.zeros((warped.shape[0], warped.shape[1]), np.float32)
    ymap = np.zeros_like(xmap)
    for yy in range(warped.shape[0]):
        shift = amplitude * math.sin((yy / warped.shape[0]) * math.pi * 1.3 + phase)
        for xx in range(warped.shape[1]):
            xmap[yy, xx] = xx + shift
            ymap[yy, xx] = yy
    warped = cv2.remap(warped, xmap, ymap, cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE)
    pil = _to_pil(warped)
    x = (canvas_w - pil.width) // 2
    y = (canvas_h - pil.height) // 2
    canvas.paste(pil, (x, y))
    return canvas


def _handwriting(page: Image.Image, rng: random.Random, notes: Sequence[str] = ()) -> Image.Image:
    layer = Image.new("RGBA", page.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    hf = font(HAND_FONT if HAND_FONT.exists() else SECOND_HAND_FONT, rng.randint(42, 60))
    ink = rng.choice([(24, 52, 160, 235), (18, 30, 120, 235), (10, 10, 10, 220)])
    candidates = list(notes) or ["bezahlt 12.10.26", "Rückfrage bei Frau Kern", "siehe Rückseite",
                                 "geprüft", "Termin notiert", "Kopie an Steuerberater"]
    positions = [(rng.randint(80, page.width - 620), rng.randint(70, 260)),
                 (rng.randint(80, page.width - 620), rng.randint(330, 520)),
                 (rng.randint(80, page.width - 720), page.height - rng.randint(180, 380))]
    for text, (x, y) in zip(rng.sample(candidates, k=min(3, len(candidates))),
                            rng.sample(positions, k=3)):
        tmp = Image.new("RGBA", (900, 160), (0, 0, 0, 0))
        td = ImageDraw.Draw(tmp)
        td.text((10, 10), text, font=hf, fill=ink)
        tmp = tmp.rotate(rng.uniform(-7, 5), resample=Image.Resampling.BICUBIC, expand=True)
        layer.alpha_composite(tmp, (x, y))
    # a signature squiggle
    sx, sy = rng.randint(180, page.width - 500), page.height - rng.randint(130, 260)
    points = []
    for i in range(9):
        points.append((sx + i * rng.randint(18, 35), sy + rng.randint(-22, 22)))
    draw.line(points, fill=ink, width=4, joint="curve")
    return Image.alpha_composite(page.convert("RGBA"), layer).convert("RGB")


def _stamp(page: Image.Image, rng: random.Random, label: str, colour=(178, 32, 40)) -> Image.Image:
    layer = Image.new("RGBA", page.size, (0, 0, 0, 0))
    size = rng.randint(420, 560)
    stamp = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stamp)
    cx, cy = size // 2, size // 2
    sd.ellipse([18, 18, size - 18, size - 18], outline=colour + (190,), width=10)
    sd.ellipse([40, 40, size - 40, size - 40], outline=colour + (150,), width=3)
    sf = font(FONT_DIR / "Arial Bold.ttf", int(size * 0.13))
    sd.text((cx, cy - 18), label, font=sf, fill=colour + (220,), anchor="mm")
    date_font = font(FONT_DIR / "Arial Bold.ttf", int(size * 0.075))
    sd.text((cx, cy + 52), "14. OKT 2026", font=date_font, fill=colour + (200,), anchor="mm")
    stamp = stamp.rotate(rng.uniform(-18, 18), resample=Image.Resampling.BICUBIC, expand=True)
    x = rng.randint(page.width // 4, page.width - stamp.width - 40)
    y = rng.randint(page.height // 4, page.height - stamp.height - 80)
    layer.alpha_composite(stamp, (x, y))
    return Image.alpha_composite(page.convert("RGBA"), layer).convert("RGB")


def _stamp_square(page: Image.Image, rng: random.Random, label: str) -> Image.Image:
    layer = Image.new("RGBA", page.size, (0, 0, 0, 0))
    w, h = rng.randint(380, 520), rng.randint(110, 150)
    stamp = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sd = ImageDraw.Draw(stamp)
    sd.rectangle([5, 5, w - 5, h - 5], outline=(180, 30, 38, 190), width=6)
    f = font(FONT_DIR / "Arial Bold.ttf", int(h * 0.45))
    sd.text((w // 2, h // 2), label, font=f, fill=(180, 30, 38, 215), anchor="mm")
    stamp = stamp.rotate(rng.uniform(-12, 12), resample=Image.Resampling.BICUBIC, expand=True)
    layer.alpha_composite(stamp, (rng.randint(page.width // 3, page.width - stamp.width - 40),
                                  rng.randint(page.height // 3, page.height - stamp.height - 80)))
    return Image.alpha_composite(page.convert("RGBA"), layer).convert("RGB")


def _degrade(page: Image.Image, rng: random.Random, condition: str) -> Image.Image:
    arr = _to_cv(page)
    if condition == "blur":
        sigma = rng.uniform(1.4, 2.6)
        arr = cv2.GaussianBlur(arr, (0, 0), sigma)
        if rng.random() < 0.5:
            k = rng.randint(5, 11)
            kernel = np.zeros((k, k), dtype=np.float32)
            kernel[k // 2, :] = 1.0 / k
            arr = cv2.filter2D(arr, -1, kernel)
    elif condition == "dark":
        arr = np.clip(arr.astype(np.float32) * rng.uniform(0.32, 0.48) + rng.uniform(-8, 8), 0, 255).astype(np.uint8)
    elif condition == "overexposed":
        arr = np.clip(arr.astype(np.float32) * rng.uniform(1.45, 1.75) + rng.uniform(20, 45), 0, 255).astype(np.uint8)
        # a bright spot from a lamp
        h, w = arr.shape[:2]
        yy, xx = np.mgrid[0:h, 0:w]
        glow = 90 * np.exp(-(((xx - w * 0.25) ** 2) / (2 * (w * 0.28) ** 2) +
                             ((yy - h * 0.2) ** 2) / (2 * (h * 0.25) ** 2)))
        arr = np.clip(arr.astype(np.float32) + glow[:, :, None], 0, 255).astype(np.uint8)
    elif condition == "rotated":
        angle = rng.choice([-1, 1]) * rng.uniform(4, 11)
        return _rotate_scene(page, rng, angle)
    elif condition == "curved":
        return _curve(page, rng)
    elif condition == "noise":
        noise = _noise(rng, rng.uniform(14, 24), arr.shape).astype(np.int16)
        arr = np.clip(arr.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        count = int(arr.shape[0] * arr.shape[1] * rng.uniform(0.0004, 0.0012))
        for _ in range(count):
            y, x = rng.randrange(arr.shape[0]), rng.randrange(arr.shape[1])
            arr[y, x] = (255, 255, 255) if rng.random() < 0.5 else (0, 0, 0)
    elif condition == "low_res":
        width = rng.randint(560, 760)
        height = int(arr.shape[0] * width / arr.shape[1])
        arr = cv2.resize(arr, (width, height), interpolation=cv2.INTER_AREA)
    elif condition == "combo":
        arr = cv2.GaussianBlur(arr, (0, 0), rng.uniform(1.2, 2.2))
        noise = _noise(rng, rng.uniform(10, 18), arr.shape).astype(np.int16)
        arr = np.clip(arr.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        h, w = arr.shape[:2]
        yy, xx = np.mgrid[0:h, 0:w]
        shade = 1.0 - 0.35 * (xx / w)
        arr = np.clip(arr.astype(np.float32) * shade[:, :, None], 0, 255).astype(np.uint8)
    return _to_pil(arr)


def _crop(page: Image.Image, rng: random.Random) -> Image.Image:
    w, h = page.size
    left = rng.randint(0, int(w * 0.11))
    right = rng.randint(0, int(w * 0.10))
    top = rng.randint(0, int(h * 0.07))
    bottom = rng.randint(0, int(h * 0.09))
    return page.crop((left, top, w - right, h - bottom))


def _jpeg_artifacts(page: Image.Image, rng: random.Random) -> Image.Image:
    # The actual quality is applied when saving; this adds a first generation of blocking.
    import io
    buffer = io.BytesIO()
    page.save(buffer, format="JPEG", quality=rng.randint(15, 28), optimize=False)
    buffer.seek(0)
    return Image.open(buffer).convert("RGB")


# --------------------------------------------------------------------------------------
# Corpus planning
# --------------------------------------------------------------------------------------


@dataclass
class Plan:
    doc_id: str
    split: str
    true_type: str
    language: str
    difficulty: str
    condition: str
    scenario_key: str
    variant: int
    paid: bool | None = None
    notes: str = ""
    handwritten: bool = False
    stamped: bool = False
    cropped: bool = False
    foreign_objects: list[str] = field(default_factory=list)


def _scenario_for(split: str, key: str) -> Scenario:
    if key == "finanzamt":
        key = "finanzamt"
    return SCENARIOS_BY_KEY[key]


def build_plan(split: str) -> list[Plan]:
    types = DEV_TYPES if split == "dev" else HOLDOUT_TYPES
    stress = DEV_STRESS if split == "dev" else HOLDOUT_STRESS
    scenarios = DEV_SCENARIOS if split == "dev" else HOLDOUT_SCENARIOS
    preferred = {s.key: s for s in scenarios}
    plans: list[Plan] = []

    def pick_scenario(true_type: str) -> Scenario:
        mapping = {
            "finanzamt": "finanzamt", "jobcenter": "jobcenter", "behoerdenbescheid": "stadt",
            "anforderung_fehlender_unterlagen": "jobcenter", "bussgeldbescheid": "stadt",
            "krankenkasse": "krankenkasse", "schule": "schule", "schulzeugnis": "schule",
            "kita_gebuehrenbescheid": "kita", "medizinische_rechnung": "praxis",
            "krankenhausrechnung": "praxis2", "krankmeldung": "praxis",
            "arbeitszeugnis": "arbeitgeber", "lohnabrechnung": "arbeitgeber",
            "arbeitsvertrag": "arbeitgeber", "mietvertrag": "hausverwaltung",
            "betriebskostenabrechnung": "hausverwaltung", "nebenkostenabrechnung": "hausverwaltung",
            "vertrag": "nordlicht", "kuendigung": "autohaus", "kuendigungsbestaetigung": "autohaus",
            "preiserhoehung": "nordlicht", "schreiben_anwalt": "weber",
            "gerichtliche_mitteilung": "gericht", "inkasso": "inkasso",
            "gewerbeanmeldung": "gewerbe", "businessplan": "nordlicht",
            "handelsregisterauszug": "handelsregister" if "handelsregister" in SCENARIOS_BY_KEY else "stadt",
            "newsletter": "werbung", "reklame": "werbung", "informationsschreiben": "versicherung",
            "widerspruch": "stadt", "angebot": "nordlicht", "gutschrift": "nordlicht",
            "zahlungsbestaetigung": "stadtwerke", "rechnung": "stadtwerke",
            "mahnung": "stadtwerke", "zahlungserinnerung": "stadtwerke",
            "quittung_kassenbon": "maler", "kontoauszug": "nordlicht",
        }
        want = mapping.get(true_type)
        if want and want in SCENARIOS_BY_KEY:
            return SCENARIOS_BY_KEY[want]
        # fall back to a scenario from this split
        return preferred[sorted(preferred)[hash(true_type) % len(preferred)]]

    # 1. clean document of every type
    for index, true_type in enumerate(types):
        scenario = pick_scenario(true_type)
        plans.append(Plan(f"{split}-{true_type}-clean-{index + 1:02d}", split, true_type, "de",
                          "clean", "clean", scenario.key, index % 3))

    # 2. degraded
    for index, (true_type, condition) in enumerate(stress["degraded"]):
        scenario = pick_scenario(true_type)
        plans.append(Plan(f"{split}-{true_type}-degraded-{condition}-{index + 1:02d}", split, true_type,
                          "de", "degraded", condition, scenario.key, index % 3))

    # 3. occluded
    for index, (true_type, condition) in enumerate(stress["occluded"]):
        scenario = pick_scenario(true_type)
        objects = [condition] if condition != "mixed" else ["hand", "coffee", "other_papers"]
        plans.append(Plan(f"{split}-{true_type}-occluded-{condition}-{index + 1:02d}", split, true_type,
                          "de", "occluded", condition, scenario.key, index % 3,
                          foreign_objects=objects))

    # 4. cropped
    for index, true_type in enumerate(stress["cropped"]):
        scenario = pick_scenario(true_type)
        plans.append(Plan(f"{split}-{true_type}-cropped-{index + 1:02d}", split, true_type, "de",
                          "cropped", "cropped", scenario.key, index % 3, cropped=True))

    # 5. multipage
    for index, true_type in enumerate(stress["multipage"]):
        scenario = pick_scenario(true_type)
        plans.append(Plan(f"{split}-{true_type}-multipage-{index + 1:02d}", split, true_type, "de",
                          "multipage", "multipage", scenario.key, index % 3,
                          notes="two pages, second page carries continuation/terms"))

    # 6. handwritten notes
    for index, true_type in enumerate(stress["handwritten"]):
        scenario = pick_scenario(true_type)
        plans.append(Plan(f"{split}-{true_type}-handwritten-{index + 1:02d}", split, true_type, "de",
                          "handwritten", "handwritten", scenario.key, index % 3, handwritten=True))

    # 7. stamps / paid & unpaid
    for index, true_type in enumerate(stress["stamped"]):
        scenario = pick_scenario(true_type)
        paid = index % 2 == 0
        plans.append(Plan(f"{split}-{true_type}-stamped-{'paid' if paid else 'open'}-{index + 1:02d}",
                          split, true_type, "de", "stamped", "paid" if paid else "open",
                          scenario.key, index % 3, paid=paid, stamped=True))

    # 8. Russian translations
    for index, true_type in enumerate(stress["russian"]):
        scenario = pick_scenario(true_type)
        plans.append(Plan(f"{split}-{true_type}-ru-{index + 1:02d}", split, true_type, "ru",
                          "russian", "russian", scenario.key, index % 3,
                          notes="Russian translation; the platform intentionally routes non-German documents to a person"))

    return plans


# --------------------------------------------------------------------------------------
# Rendering and writing
# --------------------------------------------------------------------------------------


def _save_image(image: Image.Image, path: Path, condition: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    quality = 92
    if condition == "jpeg":
        quality = 22
    elif condition in ("low_res", "combo", "noise"):
        quality = 55
    elif condition == "blur":
        quality = 70
    if path.suffix.lower() in (".jpg", ".jpeg"):
        image.save(path, format="JPEG", quality=quality, optimize=False, subsampling=2)
    else:
        image.save(path, format="PNG", optimize=False)


def _save_pdf(images: Sequence[Image.Image], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    rgb = [image.convert("RGB") for image in images]
    # Pillow otherwise stamps the current time into CreationDate/ModDate, which makes
    # the same document produce different bytes on every regeneration. A fixed epoch
    # keeps the corpus byte-for-byte reproducible.
    fixed = time.gmtime(0)
    rgb[0].save(path, format="PDF", resolution=DPI, save_all=True, append_images=rgb[1:],
                quality=85, creationDate=fixed, modDate=fixed,
                producer="Buerokratie-Copilot quality corpus", creator="generate_corpus.py")


def _layout_for(doc: DocContent, plan: Plan, rng: random.Random) -> Layout:
    if plan.language == "ru":
        return RUSSIAN_LAYOUT
    return LAYOUTS[rng.randrange(len(LAYOUTS))]


def render_document(plan: Plan) -> tuple[list[Image.Image], DocContent]:
    scenario = _scenario_for(plan.split, plan.scenario_key)
    rng = random.Random(f"{SEED}:{plan.doc_id}:layout")
    if plan.language == "ru":
        builder = RU_BUILDERS[plan.true_type]
        content = builder(scenario, rng)
    else:
        builder = BUILDERS[plan.true_type]
        content = builder(scenario, rng, plan.variant, plan.language)
    layout = _layout_for(content, plan, rng)
    pages = render(content.blocks, layout, scenario.name)

    # A multipage document gets a second page from the builder; if the builder did not
    # produce one (e.g. a naturally short type), duplicate the important data onto page 2
    # instead of pretending a one-page document is multipage.
    if plan.difficulty == "multipage" and len(pages) < 2:
        extra = Image.new("RGB", (PAGE_W, PAGE_H), "white")
        draw = ImageDraw.Draw(extra)
        f = font(layout.bold, 40)
        body = font(layout.regular, 27)
        draw.text((layout.margin, layout.margin), scenario.name, font=font(layout.bold, 30), fill=INK)
        draw.text((layout.margin, layout.margin + 80), "Anlage / Fortsetzung", font=f, fill=INK)
        yy = layout.margin + 190
        for line in [
            "Diese Seite ergänzt das vorstehende Schreiben.",
            "Die Vereinbarung gilt auch für die auf dieser Seite genannten Bedingungen.",
            "Änderungen und Ergänzungen bedürfen der Schriftform.",
            "Gerichtsstand ist der Sitz des Auftragnehmers.",
            "Die Anlagen sind Bestandteil des Dokuments.",
        ]:
            for wrapped in _wrap(draw, line, body, PAGE_W - 2 * layout.margin):
                draw.text((layout.margin, yy), wrapped, font=body, fill=INK)
                yy += 42
            yy += 18
        pages.append(extra)

    # Stress conditions
    processed: list[Image.Image] = []
    for index, page in enumerate(pages):
        rng_page = random.Random(f"{SEED}:{plan.doc_id}:page{index}:{plan.condition}")
        current = page
        if plan.difficulty == "degraded":
            if plan.condition in ("rotated", "curved"):
                current = _degrade(current, rng_page, plan.condition)
            else:
                current = _degrade(current, rng_page, plan.condition)
        elif plan.difficulty == "occluded":
            current = _scene(current, rng_page, plan.foreign_objects)
        elif plan.difficulty == "cropped":
            current = _crop(current, rng_page)
        elif plan.difficulty == "handwritten":
            current = _handwriting(current, rng_page)
        elif plan.difficulty == "stamped":
            if plan.paid:
                current = _stamp(current, rng_page, "BEZAHLT", (30, 120, 55))
            else:
                current = _stamp_square(current, rng_page, "OFFEN")
            if plan.true_type == "mahnung":
                current = _stamp_square(current, rng_page, "ÜBERFÄLLIG")
        elif plan.difficulty == "multipage":
            # A light photo of page 2 makes the multi-page case realistic without
            # destroying the recognisable content.
            if index == 1:
                current = _scene(current, rng_page, ["shadow"])
        elif plan.difficulty == "russian":
            current = current
        if plan.condition == "jpeg":
            current = _jpeg_artifacts(current, rng_page)
        processed.append(current)
    return processed, content


EXPECTED_KEYS: dict[str, dict[str, str | None]] = {
    "rechnung": {"number": "Rechnungsnummer", "date": "Rechnungsdatum", "amount": "Brutto"},
    "mahnung": {"number": "Rechnungsnummer", "date": "Frist", "amount": "OffenerBetrag"},
    "zahlungserinnerung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "quittung_kassenbon": {"number": None, "date": "Schreibendatum", "amount": "Betrag"},
    "kontoauszug": {"number": None, "date": None, "amount": "Saldo"},
    "angebot": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "gutschrift": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "zahlungsbestaetigung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "lohnabrechnung": {"number": None, "date": None, "amount": "Betrag"},
    "vertrag": {"number": None, "date": "Beginn", "amount": "Kosten"},
    "mietvertrag": {"number": None, "date": None, "amount": "Betrag"},
    "kuendigung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "kuendigungsbestaetigung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "preiserhoehung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "arbeitsvertrag": {"number": None, "date": None, "amount": "Betrag"},
    "behoerdenbescheid": {"number": "Aktenzeichen", "date": "Bescheiddatum", "amount": None},
    "anforderung_fehlender_unterlagen": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "finanzamt": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "krankenkasse": {"number": None, "date": "Schreibendatum", "amount": "Betrag"},
    "jobcenter": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "widerspruch": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "bussgeldbescheid": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "betriebskostenabrechnung": {"number": None, "date": "Schreibendatum", "amount": "Saldo"},
    "nebenkostenabrechnung": {"number": None, "date": "Schreibendatum", "amount": "Saldo"},
    "arbeitszeugnis": {"number": None, "date": "Schreibendatum", "amount": None},
    "krankmeldung": {"number": None, "date": "Schreibendatum", "amount": None},
    "medizinische_rechnung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "krankenhausrechnung": {"number": None, "date": None, "amount": "Betrag"},
    "kita_gebuehrenbescheid": {"number": None, "date": "Schreibendatum", "amount": "Betrag"},
    "schule": {"number": None, "date": "Schreibendatum", "amount": None},
    "schulzeugnis": {"number": None, "date": "Schreibendatum", "amount": None},
    "newsletter": {"number": None, "date": "Schreibendatum", "amount": None},
    "reklame": {"number": None, "date": None, "amount": None},
    "informationsschreiben": {"number": None, "date": "Schreibendatum", "amount": None},
    "schreiben_anwalt": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "gerichtliche_mitteilung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "inkasso": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": "Betrag"},
    "gewerbeanmeldung": {"number": "Aktenzeichen", "date": "Schreibendatum", "amount": None},
    "businessplan": {"number": None, "date": None, "amount": None},
    "handelsregisterauszug": {"number": None, "date": "Schreibendatum", "amount": None},
}


def normalise_expected_fields(content: DocContent, true_type: str) -> dict[str, str]:
    """Keep only the fields a document really has, under the key its type defines.

    The pipeline extracts against the *declared* field set of the type. A generic type
    has no ``Rechnungsnummer`` field, so expecting that key would measure a catalogue
    mismatch rather than a reading failure. Values come from the builder, the key from
    this table.
    """
    keys = EXPECTED_KEYS[true_type]
    fields: dict[str, str] = {}
    if keys.get("number") and content.number:
        fields[keys["number"]] = content.number
    if keys.get("date") and content.doc_date:
        fields[keys["date"]] = content.doc_date
    if keys.get("amount") and content.amount:
        fields[keys["amount"]] = content.amount
    if true_type == "vertrag":
        for key in ("Laufzeit", "Kuendigungsfrist"):
            if key in content.fields:
                fields[key] = content.fields[key]
    if true_type == "behoerdenbescheid" and "Behoerde" in content.fields:
        fields["Behoerde"] = content.fields["Behoerde"]
    if true_type == "krankmeldung" and "Frist" in content.fields:
        fields["Frist"] = content.fields["Frist"]
    return fields


def write_corpus(split: str, clean: bool) -> None:
    plans = build_plan(split)
    split_dir = OUT_ROOT / split
    if clean and split_dir.exists():
        shutil.rmtree(split_dir)
    split_dir.mkdir(parents=True, exist_ok=True)

    records = []
    for plan in plans:
        pages, content = render_document(plan)
        if plan.difficulty == "multipage":
            filename = f"{plan.doc_id}.pdf"
            mime = "application/pdf"
            path = split_dir / filename
            _save_pdf(pages, path)
        else:
            # Clean scans are a mix of JPEG and PNG; photographs are JPEG.
            suffix = ".png" if plan.difficulty == "clean" and len(records) % 4 == 0 else ".jpg"
            filename = f"{plan.doc_id}{suffix}"
            mime = "image/png" if suffix == ".png" else "image/jpeg"
            path = split_dir / filename
            _save_image(pages[0], path, plan.condition)

        record = {
            "id": plan.doc_id,
            "file": f"{split}/{filename}",
            "split": split,
            "language": plan.language,
            "trueType": plan.true_type,
            "trueCategory": CATEGORY[plan.true_type],
            "difficulty": plan.difficulty,
            "condition": plan.condition,
            "pages": len(pages),
            "multipage": plan.difficulty == "multipage",
            "paid": plan.paid,
            "handwritten": plan.handwritten,
            "stamped": plan.stamped,
            "cropped": plan.cropped,
            "foreignObjects": plan.foreign_objects,
            "expectedFields": normalise_expected_fields(content, plan.true_type),
            "expectedSupplier": content.supplier,
            "expectedNumber": content.number,
            "expectedDate": content.doc_date,
            "expectedAmount": content.amount,
            "expectedExpenseCategory": content.expense_category,
            "expenseText": content.expense_text,
            "acceptableTypes": ACCEPTABLE.get(plan.true_type, []),
            "keywords": content.keywords,
            "notes": plan.notes,
            "mime": mime,
            "bytes": path.stat().st_size,
        }
        records.append(record)
        print(f"  {plan.doc_id:65s} {path.stat().st_size / 1024:7.1f} KB", flush=True)

    manifest = {
        "version": CORPUS_VERSION,
        "seed": SEED,
        "dpi": DPI,
        "generatedWith": "scripts/quality/generate_corpus.py",
        "split": split,
        "count": len(records),
        "documents": records,
    }
    manifest_path = OUT_ROOT / f"manifest-{split}.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {manifest_path} ({len(records)} documents)")


def merge_manifests() -> None:
    all_records = []
    splits = []
    for split in ("dev", "holdout"):
        path = OUT_ROOT / f"manifest-{split}.json"
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        all_records.extend(data["documents"])
        splits.append({"name": split, "count": data["count"]})
    manifest = {
        "version": CORPUS_VERSION,
        "seed": SEED,
        "dpi": DPI,
        "generatedWith": "scripts/quality/generate_corpus.py",
        "splits": splits,
        "count": len(all_records),
        "documents": all_records,
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {MANIFEST} ({len(all_records)} documents)")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--split", choices=["dev", "holdout", "all"], default="all")
    parser.add_argument("--clean", action="store_true", help="delete the generated split directories first")
    args = parser.parse_args(argv)

    if not FALLBACK_FONT.exists():
        print(f"font not found: {FALLBACK_FONT}", file=sys.stderr)
        return 2
    if not cv2.__version__:
        print("opencv is required", file=sys.stderr)
        return 2

    splits = ["dev", "holdout"] if args.split == "all" else [args.split]
    for split in splits:
        write_corpus(split, args.clean)
    if len(splits) == 2:
        merge_manifests()
    elif MANIFEST.exists():
        # Keep the merged manifest useful when only one split was regenerated and the
        # other split is still on disk.
        merge_manifests()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
