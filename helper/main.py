from __future__ import annotations

import os
import re
from collections import defaultdict
from datetime import datetime
from typing import Any

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from cache import clear_cache, get_cached, is_safe_report_key, make_cache_key, set_cached
from models import CacheLookupRequest, ParsedReport, ParseReportRequest, SummarizeRequest
from parsers.culture_parser import parse_cultures
from parsers.lab_parser import infer_report_tags, infer_report_type, parse_lab_parameters
from parsers.pdf_text import decode_report_bytes, detect_non_report_payload, extract_text_from_bytes


app = FastAPI(title="NIMS Fast Summary Helper", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "chrome-extension://*",
        "https://nimsts.edu.in",
        "https://www.nimsts.edu.in",
        "http://127.0.0.1:8765",
        "null",
    ],
    allow_origin_regex=r"chrome-extension://.*",
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ok": True}


@app.post("/parse-report")
def parse_report(payload: ParseReportRequest) -> dict[str, Any]:
    pdf_bytes = decode_report_bytes(payload.pdf_base64)
    cache_key = make_cache_key(
        payload.report_id, pdf_bytes, payload.source_url, payload.date_sent, payload.report_name
    )
    cached = get_cached(cache_key)
    if cached:
        cached["cached"] = True
        return cached

    text = payload.text or ""
    errors: list[str] = []
    if not text:
        text, errors = extract_text_from_bytes(pdf_bytes)

    non_report_error = detect_non_report_payload(text, payload.content_type)
    if non_report_error:
        parsed = ParsedReport(
            report_id=payload.report_id or cache_key,
            report_name=payload.report_name,
            date_sent=payload.date_sent,
            report_type="other",
            report_tags=["other"],
            parameters=[],
            culture=None,
            raw_text_preview="",
            errors=[non_report_error],
        ).model_dump()
        set_cached(cache_key, parsed)
        return parsed

    report_tags = infer_report_tags(payload.report_name, text)
    report_type = infer_report_type(payload.report_name, text)
    parameters = parse_lab_parameters(text, payload.date_sent)
    culture_results = parse_cultures(text, payload.date_sent) if "culture" in report_tags else []
    culture = culture_results[0] if culture_results else None
    preview = deidentify(text)[:500]

    parsed = ParsedReport(
        report_id=payload.report_id or cache_key,
        report_name=payload.report_name,
        date_sent=payload.date_sent,
        report_type=report_type,
        report_tags=report_tags,
        parameters=parameters,
        culture=culture,
        culture_results=culture_results,
        raw_text_preview=preview,
        errors=errors,
    ).model_dump()
    set_cached(cache_key, parsed)
    return parsed


@app.post("/summarize")
def summarize(payload: SummarizeRequest) -> dict[str, Any]:
    reports = [coerce_report(report) for report in payload.reports]
    if payload.mode == "cultures_only":
        selected = [r for r in reports if has_tag(r, "culture")]
    elif payload.mode == "fast":
        selected = select_fast_reports(reports)
    else:
        selected = reports

    return {
        "source_reports": source_report_rows(selected),
        "lab_trend_table": build_lab_trend_table(selected),
        "culture_table": build_culture_table(selected),
        "interpretation": build_interpretation(selected),
        "ai_note": ai_note(),
    }


@app.post("/cache-lookup")
def cache_lookup(payload: CacheLookupRequest) -> dict[str, Any]:
    hits: dict[str, Any] = {}
    misses: list[str] = []
    for item in payload.reports:
        report_key = item.report_key.strip()
        if not is_safe_report_key(report_key):
            misses.append(report_key)
            continue
        cached = get_cached(report_key)
        if cached:
            cached["cached"] = True
            hits[report_key] = cached
        else:
            misses.append(report_key)
    return {"hits": hits, "misses": misses}


@app.post("/clear-cache")
def clear() -> dict[str, bool]:
    clear_cache()
    return {"ok": True}


def coerce_report(report: ParsedReport | dict[str, Any]) -> dict[str, Any]:
    if isinstance(report, ParsedReport):
        return report.model_dump()
    return dict(report)


def select_fast_reports(reports: list[dict[str, Any]]) -> list[dict[str, Any]]:
    limits = {"cbc": 5, "rft": 5, "electrolytes": 5, "lft": 5, "coagulation": 3}
    selected: list[dict[str, Any]] = []
    counts: defaultdict[str, int] = defaultdict(int)
    seen: set[str] = set()
    for report in sorted(reports, key=lambda r: date_key(r.get("date_sent", "")), reverse=True):
        key = "|".join(
            [
                report.get("report_name", ""),
                report.get("date_sent", ""),
                report.get("report_id", ""),
            ]
        )
        if key in seen:
            continue
        seen.add(key)
        report_tags = report.get("report_tags") or [report.get("report_type", "other")]
        name = report.get("report_name", "").lower()
        if "culture" in report_tags:
            selected.append(report)
        elif any(marker in name for marker in ("crp", "procalcitonin")):
            selected.append(report)
        else:
            included = False
            for tag in report_tags:
                if counts[tag] < limits.get(tag, 0):
                    included = True
                    counts[tag] += 1
            if included:
                selected.append(report)
    return selected


def source_report_rows(reports: list[dict[str, Any]]) -> list[dict[str, str]]:
    rows = []
    for report in reports:
        errors = report.get("errors") or []
        rows.append(
            {
                "date_sent": report.get("date_sent", ""),
                "report_name": report.get("report_name", ""),
                "type": report.get("report_type", "other"),
                "tags": ", ".join(report.get("report_tags") or [report.get("report_type", "other")]),
                "status": "cached" if report.get("cached") else ("error" if errors else "parsed"),
                "notes": "; ".join(errors),
            }
        )
    return rows


def build_lab_trend_table(reports: list[dict[str, Any]]) -> dict[str, Any]:
    dates = sorted(
        {r.get("date_sent", "") for r in reports if r.get("parameters")},
        key=date_key,
        reverse=True,
    )
    by_param: dict[str, dict[str, dict[str, str]]] = defaultdict(dict)
    for report in reports:
        for param in report.get("parameters") or []:
            date = param.get("date_sent") or report.get("date_sent", "")
            by_param[param.get("canonical_name", param.get("name", ""))][date] = param

    rows = []
    for parameter, date_map in sorted(by_param.items()):
        values = []
        for date in dates:
            param = date_map.get(date)
            values.append(format_param_cell(param) if param else "")
        rows.append({"parameter": parameter, "values": values, "trend": infer_trend(date_map, dates)})
    return {"columns": dates, "rows": rows}


def build_culture_table(reports: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows = []
    for report in reports:
        for culture in culture_items(report):
            sensitivity = culture.get("sensitivity_summary") or {}
            organisms = culture.get("organisms") or []
            organism = culture.get("organism") or ", ".join(organisms)
            site_specimen = culture.get("site_specimen") or " / ".join(
                part for part in [culture.get("site", ""), culture.get("specimen", "")] if part
            )
            rows.append(
                {
                    "date_sent": culture.get("date_sent") or report.get("date_sent", ""),
                    "requisition_date": culture.get("requisition_date", ""),
                    "collection_date": culture.get("collection_date", ""),
                    "reporting_date": culture.get("reporting_date", ""),
                    "culture_no": culture.get("culture_no") or culture.get("culture_number", ""),
                    "culture_number": culture.get("culture_no") or culture.get("culture_number", ""),
                    "specimen_no": culture.get("specimen_no", ""),
                    "sample_processed": culture.get("sample_processed", ""),
                    "site_specimen": site_specimen,
                    "culture_type": culture.get("culture_type", ""),
                    "bottle_set": display_bottle_set(culture.get("bottle_set", "")),
                    "bottle_set_code": culture.get("bottle_set", ""),
                    "status": culture.get("report_status", "unknown"),
                    "report_status": culture.get("report_status", "unknown"),
                    "result": culture.get("result") or culture.get("result_status", "unknown"),
                    "growth": culture.get("growth_quantity", ""),
                    "growth_quantity": culture.get("growth_quantity", ""),
                    "organism": organism,
                    "comment": culture.get("comment", ""),
                    "sensitivity_summary": format_sensitivity(sensitivity),
                    "susceptible_antibiotics": culture.get("susceptible_antibiotics", []),
                    "resistant_antibiotics": culture.get("resistant_antibiotics", []),
                    "intermediate_antibiotics": culture.get("intermediate_antibiotics", []),
                    "raw_evidence_short": culture.get("raw_evidence_short", ""),
                }
            )
    return sorted(
        rows,
        key=lambda r: (date_key(r.get("reporting_date") or r.get("date_sent", "")), r.get("culture_no", ""), r.get("bottle_set_code", "")),
        reverse=True,
    )


def build_interpretation(reports: list[dict[str, Any]]) -> list[str]:
    trend_table = build_lab_trend_table(reports)
    bullets: list[str] = []
    for item in microbiology_interpretation(build_culture_table(reports)):
        bullets.append(item)
        if len(bullets) >= 5:
            break
    for row in trend_table["rows"]:
        if row["trend"] in {"rising", "falling", "variable"}:
            bullets.append(f"{row['parameter']} trend is {row['trend']} across available reports.")
        if len(bullets) >= 4:
            break
    if not bullets:
        bullets.append("AI interpretation disabled; structured tables generated locally.")
    return bullets[:8]


def format_param_cell(param: dict[str, Any] | None) -> str:
    if not param:
        return ""
    value = param.get("value", "")
    unit = param.get("unit", "")
    flag = param.get("abnormal_flag", "unknown")
    suffix = f" [{flag}]" if flag in {"low", "high"} else ""
    return f"{value} {unit}".strip() + suffix


def infer_trend(date_map: dict[str, dict[str, Any]], dates: list[str]) -> str:
    values = []
    for date in reversed(dates):
        param = date_map.get(date)
        if not param:
            continue
        try:
            values.append(float(str(param.get("value", "")).replace(",", "")))
        except ValueError:
            pass
    if len(values) < 2:
        return "insufficient data"
    deltas = [b - a for a, b in zip(values, values[1:])]
    threshold = max(abs(values[-1]) * 0.03, 0.05)
    if all(delta > threshold for delta in deltas):
        return "rising"
    if all(delta < -threshold for delta in deltas):
        return "falling"
    if all(abs(delta) <= threshold for delta in deltas):
        return "stable"
    return "variable"


def format_sensitivity(sensitivity: dict[str, list[str]] | str) -> str:
    if isinstance(sensitivity, str):
        return sensitivity or "No susceptibility table found"
    parts = []
    labels = [("Sensitive", "sensitive"), ("Resistant", "resistant"), ("Intermediate", "intermediate")]
    for label, key in labels:
        values = sensitivity.get(key) or []
        if values:
            parts.append(f"{label}: {', '.join(values)}")
    return "; ".join(parts) if parts else "No susceptibility table found"


def culture_items(report: dict[str, Any]) -> list[dict[str, Any]]:
    items = report.get("culture_results") or []
    if items:
        return [dict(item) for item in items if item]
    culture = report.get("culture")
    return [dict(culture)] if culture else []


def display_bottle_set(value: str) -> str:
    match = re.match(r"set_(\d+)_bottle_(\d+)$", value or "")
    if not match:
        return value or ""
    return f"Set {match.group(1)} Bottle {match.group(2)}"


def microbiology_interpretation(rows: list[dict[str, Any]]) -> list[str]:
    bullets: list[str] = []
    for row in rows:
        if row.get("result") != "positive":
            continue
        site = row.get("site_specimen") or "culture"
        date = row.get("collection_date") or row.get("date_sent") or row.get("reporting_date") or "available date"
        growth = f"{row.get('growth')} growth of " if row.get("growth") else "growth of "
        organism = row.get("organism") or "an organism"
        comment = f"; {row.get('comment')}" if row.get("comment") else ""
        bullets.append(f"{site} culture on {date}: reported {growth}{organism}{comment}.")
    blood_no_growth: defaultdict[str, int] = defaultdict(int)
    for row in rows:
        if row.get("result") == "no_growth" and "blood" in (row.get("site_specimen", "") + row.get("culture_type", "")).lower():
            key = row.get("collection_date") or row.get("date_sent") or row.get("reporting_date") or "available date"
            blood_no_growth[key] += 1
    for date, count in blood_no_growth.items():
        suffix = "report" if count == 1 else "bottle/set reports"
        bullets.append(f"Blood cultures on {date}: no aerobic growth reported across {count} {suffix}.")
    for row in rows:
        if row.get("result") == "pending":
            site = row.get("site_specimen") or "Culture"
            date = row.get("collection_date") or row.get("date_sent") or ""
            bullets.append(f"{site} culture on {date}: result pending.")
    return bullets[:5]


def has_tag(report: dict[str, Any], tag: str) -> bool:
    return tag in (report.get("report_tags") or [report.get("report_type", "other")])


def date_key(value: str) -> datetime:
    value = (value or "").strip()
    for fmt in ("%d-%b-%Y %H:%M", "%d-%b-%y %H:%M", "%d-%b-%Y", "%d-%b-%y", "%d/%m/%Y", "%Y-%m-%d", "%d-%m-%Y"):
        try:
            return datetime.strptime(value, fmt)
        except ValueError:
            continue
    return datetime.min


def deidentify(text: str) -> str:
    masked = re.sub(r"\bCR\s*(?:No|Number)?\s*[:\-]?\s*\d+\b", "CR No: [MASKED]", text, flags=re.I)
    masked = re.sub(r"\b\d{10}\b", "[PHONE MASKED]", masked)
    masked = re.sub(r"(?im)^(patient\s*name|name)\s*[:\-].*$", r"\1: [MASKED]", masked)
    masked = re.sub(r"(?im)^address\s*[:\-].*$", "Address: [MASKED]", masked)
    return masked


def ai_note() -> str:
    if os.getenv("OPENAI_API_KEY"):
        return "Optional AI key detected; MVP currently returns rule-based interpretation only."
    return "AI interpretation disabled; structured tables generated locally."

