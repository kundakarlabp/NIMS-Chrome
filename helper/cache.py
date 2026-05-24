from __future__ import annotations

import hashlib
import json
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
    if report_id:
        return f"report_id:{report_id}"
    if pdf_bytes:
        return "sha256:" + hashlib.sha256(pdf_bytes).hexdigest()
    fallback = "|".join([source_url, date_sent, report_name])
    return "meta:" + hashlib.sha256(fallback.encode("utf-8")).hexdigest()


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

