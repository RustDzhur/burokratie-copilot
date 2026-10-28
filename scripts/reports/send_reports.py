#!/usr/bin/env python3
"""Build the report documents and mail them to the owner.

Designed to run unattended (cron / systemd timer). Reads SMTP credentials from
the environment:

    SMTP_HOST (default smtp.gmail.com)
    SMTP_PORT (default 587)
    SMTP_USER
    SMTP_PASSWORD
    REPORTS_TO (comma separated, default dzhuraievrustem@gmail.com)

Usage:
    send_reports.py --repo /repo [--dry-run] [--out /tmp/firmspace-reports]
"""
from __future__ import annotations

import argparse
import mimetypes
import os
import smtplib
import sys
from email.message import EmailMessage
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from md_to_docx import convert  # noqa: E402

DEFAULT_REPORTS = [
    "docs/REPORT_2026-10-27.md",
    "docs/agents/AGENT_VERIFICATION.md",
    "docs/ELSTER_MAPPING.md",
    "docs/ELSTER_README.md",
    "docs/quality/DOCUMENT_QUALITY.md",
    "docs/HOW_IT_WORKS.md",
]
ATTACHMENTS = ["docs/ELSTER_CATEGORIES.csv"]


def build_documents(repo: Path, out_dir: Path) -> list[Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    produced: list[Path] = []
    for rel in DEFAULT_REPORTS:
        src = repo / rel
        if not src.exists():
            print(f"skip missing report: {src}")
            continue
        doc = convert(src.read_text(encoding="utf-8"), src.stem)
        target = out_dir / f"{src.stem}.docx"
        doc.save(target)
        produced.append(target)
    for rel in ATTACHMENTS:
        src = repo / rel
        if src.exists():
            produced.append(src)
    return produced


def send(files: list[Path], subject: str) -> int:
    host = os.environ.get("SMTP_HOST", "smtp.gmail.com")
    port = int(os.environ.get("SMTP_PORT", "587"))
    user = os.environ.get("SMTP_USER", "")
    password = os.environ.get("SMTP_PASSWORD", "")
    recipients = [
        part.strip()
        for part in os.environ.get("REPORTS_TO", "dzhuraievrustem@gmail.com").split(",")
        if part.strip()
    ]
    if not user or not password:
        print("SMTP_USER/SMTP_PASSWORD are not set — documents built, nothing sent.")
        return 2

    message = EmailMessage()
    message["From"] = user
    message["To"] = ", ".join(recipients)
    message["Subject"] = subject
    message.set_content(
        "Добрый день!\n\n"
        "Во вложении отчёты Firmspace:\n"
        + "\n".join(f"  • {path.name}" for path in files)
        + "\n\nОтчёты сформированы автоматически.",
    )
    for path in files:
        ctype, _ = mimetypes.guess_type(path.name)
        maintype, subtype = (ctype or "application/octet-stream").split("/", 1)
        message.add_attachment(
            path.read_bytes(), maintype=maintype, subtype=subtype, filename=path.name
        )

    with smtplib.SMTP(host, port, timeout=30) as client:
        client.starttls()
        client.login(user, password)
        client.send_message(message)
    print(f"sent {len(files)} attachment(s) to {', '.join(recipients)}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=".")
    parser.add_argument("--out", default="/tmp/firmspace-reports")
    parser.add_argument("--subject", default="Firmspace: отчёты (агенты, документы, ELSTER)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    repo = Path(args.repo).resolve()
    files = build_documents(repo, Path(args.out))
    if not files:
        print("nothing to send")
        return 1
    print("documents:", ", ".join(path.name for path in files))
    if args.dry_run:
        return 0
    return send(files, args.subject)


if __name__ == "__main__":
    sys.exit(main())
