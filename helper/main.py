from __future__ import annotations

import logging
import os
import re
import secrets
import time
import uuid
from collections import defaultdict
from datetime import datetime
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from cache import clear_cache, get_cached, is_safe_report_key, make_cache_key, set_cached
from models import CacheLookupRequest, ParsedReport, ParseReportRequest, SummarizeRequest
from parsers.culture_parser import parse_cultures
from parsers.lab_parser import infer_report_tags, infer_report_type, parse_lab_parameters
from parsers.pdf_text import decode_report_bytes, detect_non_report_payload, extract_text_from_bytes


logger = logging.getLogger("nims_helper")


def env_flag(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def remote_mode() -> bool:
    return env_flag("NIMS_HELPER_REMOTE_MODE", False)


def cache_enabled() -> bool:
    if remote_mode():
        return env_flag("NIMS_HELPER_CACHE_ENABLED", False)
    return env_flag("NIMS_HELPER_CACHE_ENABLED", True)


def max_body_bytes() -> int:
    try:
        megabytes = int(os.getenv("NIMS_HELPER_MAX_BODY_MB", "25"))
    except ValueError:
        megabytes = 25
    return max(megabytes, 1) * 1024 * 1024


def allowed_origins() -> list[str]:
    configured = [
        origin.strip()
        for origin in os.getenv("NIMS_HELPER_ALLOWED_ORIGINS", "").split(",")
        if origin.strip() and origin.strip() != "*"
    ]
    if remote_mode():
        return configured
    return [
        "https://nimsts.edu.in",
        "https://www.nimsts.edu.in",
        "http://127.0.0.1:8765",
        "null",
    ]


def require_api_key(x_nims_helper_key: str | None = Header(default=None)) -> None:
    if not remote_mode():
        return
    expected = os.getenv("NIMS_HELPER_API_KEY", "")
    if expected and x_nims_helper_key and secrets.compare_digest(x_nims_helper_key, expected):
        return
    raise HTTPException(status_code=401, detail="unauthorized")


app = FastAPI(title="NIMS Fast Summary Helper", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=allowed_origins(),
    allow_origin_regex=None if remote_mode() else r"chrome-extension://.*",
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def safety_middleware(request: Request, call_next):
    request_id = str(uuid.uuid4())
    start = time.perf_counter()
    content_length = request.headers.get("content-length")
    try:
        length_value = int(content_length or "0")
    except ValueError:
        length_value = 0
    if length_value > max_body_bytes():
        return JSONResponse(
            status_code=413,
            content={"ok": False, "error": "request body too large", "request_id": request_id},
            headers={"X-Request-ID": request_id},
        )
    try:
        response = await call_next(request)
    except HTTPException:
        raise
    except Exception as exc:
        duration_ms = round((time.perf_counter() - start) * 1000)
        logger.exception(
            "helper_error request_id=%s endpoint=%s error_class=%s duration_ms=%s",
            request_id,
            request.url.path,
            exc.__class__.__name__,
            duration_ms,
        )
        return JSONResponse(
            status_code=500,
            content={"ok": False, "error": "internal server error", "request_id": request_id},
            headers={"X-Request-ID": request_id},
        )
    response.headers["X-Request-ID"] = request_id
    duration_ms = round((time.perf_counter() - start) * 1000)
    logger.info(
        "helper_request request_id=%s endpoint=%s status=%s content_type=%s payload_size=%s duration_ms=%s",
        request_id,
        request.url.path,
        response.status_code,
        request.headers.get("content-type", ""),
        length_value,
        duration_ms,
    )
    return response


@app.exception_handler(HTTPException)
async def http_exception_handler(_request: Request, exc: HTTPException):
    if exc.status_code == 401 and exc.detail == "unauthorized":
        return JSONResponse(status_code=401, content={"ok": False, "error": "unauthorized"})
    return JSONResponse(status_code=exc.status_code, content={"ok": False, "error": str(exc.detail)})


@app.get("/health")
def health() -> dict[str, bool]:
    return {"ok": True}


@app.post("/parse-report")
def parse_report(payload: ParseReportRequest, _auth: None = Depends(require_api_key)) -> dict[str, Any]:
    pdf_bytes = decode_report_bytes(payload.pdf_base64)
    cache_key = make_cache_key(
        payload.report_id, pdf_bytes, payload.source_url, payload.date_sent, payload.report_name
    )
    cached = get_cached(cache_key) if cache_enabled() else None
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
        if cache_enabled():
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
    if cache_enabled():
        set_cached(cache_key, parsed)
    return parsed


@app.post("/summarize")
def summarize(payload: SummarizeRequest, _auth: None = Depends(require_api_key)) -> dict[str, Any]:
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
def cache_lookup(payload: CacheLookupRequest, _auth: None = Depends(require_api_key)) -> dict[str, Any]:
    hits: dict[str, Any] = {}
    misses: list[str] = []
    for item in payload.reports:
        report_key = item.report_key.strip()
        if not cache_enabled() or not is_safe_report_key(report_key):
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
def clear(_auth: None = Depends(require_api_key)) -> dict[str, bool]:
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
    rows_by_key: dict[str, dict[str, Any]] = {}
    for report in reports:
        for culture in culture_items(report):
            sensitivity = culture.get("sensitivity_summary") or {}
            organisms = culture.get("organisms") or []
            organism = culture.get("organism") or ", ".join(organisms)
            site_specimen = culture.get("site_specimen") or " / ".join(
                part for part in [culture.get("site", ""), culture.get("specimen", "")] if part
            )
            row = {
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
                "comment": normalize_comment(culture.get("comment", "")),
                "sensitivity_summary": format_sensitivity(sensitivity),
                "susceptible_antibiotics": culture.get("susceptible_antibiotics", []),
                "resistant_antibiotics": culture.get("resistant_antibiotics", []),
                "intermediate_antibiotics": culture.get("intermediate_antibiotics", []),
                "raw_evidence_short": normalize_space(culture.get("raw_evidence_short", "")),
            }
            row["culture_row_key"] = culture_row_key(row)
            rows_by_key.setdefault(row["culture_row_key"], row)
    return sorted(rows_by_key.values(), key=culture_sort_key)


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
        culture_no = f" {row.get('culture_no')}" if row.get("culture_no") else ""
        growth = f"{row.get('growth')} growth of " if row.get("growth") else "growth of "
        organism = row.get("organism") or "an organism"
        sensitivity = row.get("sensitivity_summary", "")
        sensitivity_note = f"; {sensitivity}" if sensitivity and sensitivity != "No susceptibility table found" else ""
        comment = comment_sentence_fragment(row.get("comment", ""))
        bullets.append(f"{site} culture{culture_no} collected {date}: {growth}{organism}{comment}{sensitivity_note}.")
    blood_groups: dict[tuple[str, str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row.get("result") == "no_growth" and "blood" in (row.get("site_specimen", "") + row.get("culture_type", "")).lower():
            key = (
                row.get("culture_no", ""),
                row.get("specimen_no", ""),
                row.get("collection_date") or row.get("date_sent") or row.get("reporting_date") or "available date",
                row.get("culture_type", ""),
            )
            blood_groups[key].append(row)
    for (culture_no, specimen_no, collection_date, _culture_type), group_rows in blood_groups.items():
        statuses = {row.get("status") or row.get("report_status") for row in group_rows}
        label = "Blood culture"
        if culture_no:
            label += f" {culture_no}"
        if specimen_no:
            label += f" / specimen {specimen_no}"
        if "final" in statuses:
            bullets.append(f"{label} collected {collection_date}: no aerobic growth in all reported bottle/set sections; final reports available.")
        elif "48_hour" in statuses:
            bullets.append(f"{label} collected {collection_date}: no aerobic growth reported at 48 hours; final report pending/expected.")
        else:
            bullets.append(f"{label} collected {collection_date}: no aerobic growth reported.")
    for row in rows:
        if row.get("result") == "pending":
            site = row.get("site_specimen") or "Culture"
            date = row.get("collection_date") or row.get("date_sent") or ""
            bullets.append(f"{site} culture on {date}: result pending.")
    return bullets[:5]


def culture_row_key(row: dict[str, Any]) -> str:
    key_parts = [
        row.get("culture_no", ""),
        row.get("specimen_no", ""),
        row.get("collection_date", ""),
        row.get("reporting_date", ""),
        row.get("culture_type", ""),
        row.get("bottle_set_code", ""),
        row.get("report_status") or row.get("status", ""),
        row.get("result", ""),
        row.get("organism", ""),
        row.get("growth_quantity") or row.get("growth", ""),
    ]
    return "|".join(normalize_space(part).lower() for part in key_parts)


def culture_sort_key(row: dict[str, Any]) -> tuple[int, int, str, str, int, int, int]:
    collection = date_key(row.get("collection_date") or row.get("date_sent") or row.get("reporting_date") or "")
    reporting = date_key(row.get("reporting_date", ""))
    return (
        -collection.toordinal(),
        -((collection.hour * 60) + collection.minute),
        row.get("culture_no", ""),
        row.get("specimen_no", ""),
        bottle_order(row.get("bottle_set_code", "")),
        status_order(row.get("status") or row.get("report_status", "")),
        -reporting.toordinal(),
    )


def bottle_order(value: str) -> int:
    order = {"set_1_bottle_1": 1, "set_1_bottle_2": 2, "set_2_bottle_1": 3, "set_2_bottle_2": 4}
    return order.get(value or "", 99)


def status_order(value: str) -> int:
    order = {"48_hour": 1, "preliminary": 2, "final": 3, "unknown": 9}
    return order.get(value or "", 9)


def normalize_comment(value: str) -> str:
    lower = normalize_space(value).lower()
    if not lower:
        return ""
    if "possible colonization/contamination" in lower and "repeat if necessary" in lower:
        return "Possible colonization/contamination; repeat if necessary."
    if "colonization or contamination" in lower and "repeat if necessary" in lower:
        return "Possible colonization/contamination; repeat if necessary."
    if lower == "repeat if necessary" or lower == "repeat if necessary.":
        return "Repeat if necessary."
    return value.strip()


def comment_sentence_fragment(value: str) -> str:
    comment = normalize_comment(value).rstrip(".")
    if not comment:
        return ""
    lower = comment.lower()
    if "possible colonization/contamination" in lower and "repeat if necessary" in lower:
        return "; report comments possible colonization/contamination and recommends repeat if necessary"
    return f"; {comment}"


def normalize_space(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


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

