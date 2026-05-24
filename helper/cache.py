from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from pathlib import Path
from typing import Any


DB_PATH = Path(__file__).with_name("cache.db")


def init_cache() -> None:
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS parsed_reports (
                cache_key TEXT PRIMARY KEY,
                parsed_json TEXT NOT NULL,
                created_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """
        )


def make_cache_key(
    report_id: str | None,
    pdf_bytes: bytes,
    source_url: str,
    date_sent: str,
    report_name: str,
) -> str:
    if is_safe_report_key(report_id):
        return report_id or ""
    if pdf_bytes:
        return "sha256:" + hashlib.sha256(pdf_bytes).hexdigest()
    if is_safe_report_id(report_id):
        stable = "|".join([report_id or "", date_sent, report_name])
        return "report_id:" + hashlib.sha256(stable.encode("utf-8")).hexdigest()
    fallback = "|".join([source_url, date_sent, report_name])
    return "meta:" + hashlib.sha256(fallback.encode("utf-8")).hexdigest()


def is_safe_report_id(report_id: str | None) -> bool:
    value = (report_id or "").strip()
    if not value:
        return False
    if re.fullmatch(r"row-\d+", value, flags=re.IGNORECASE):
        return False
    if re.fullmatch(r"(?:index|idx|generated|temp|tmp)[-_]?\d+", value, flags=re.IGNORECASE):
        return False
    if re.fullmatch(r"\d{1,3}", value):
        return False
    return bool(re.search(r"[A-Za-z0-9]", value)) and len(value) >= 4


def is_safe_report_key(report_id: str | None) -> bool:
    value = (report_id or "").strip()
    return bool(re.fullmatch(r"report_key:[a-f0-9]{64}", value))


def get_cached(cache_key: str) -> dict[str, Any] | None:
    init_cache()
    with sqlite3.connect(DB_PATH) as conn:
        row = conn.execute(
            "SELECT parsed_json FROM parsed_reports WHERE cache_key = ?", (cache_key,)
        ).fetchone()
    if not row:
        return None
    return json.loads(row[0])


def set_cached(cache_key: str, parsed: dict[str, Any]) -> None:
    init_cache()
    clean = dict(parsed)
    clean["cached"] = False
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute(
            """
            INSERT OR REPLACE INTO parsed_reports(cache_key, parsed_json)
            VALUES(?, ?)
            """,
            (cache_key, json.dumps(clean, ensure_ascii=False)),
        )


def clear_cache() -> None:
    init_cache()
    with sqlite3.connect(DB_PATH) as conn:
        conn.execute("DELETE FROM parsed_reports")

