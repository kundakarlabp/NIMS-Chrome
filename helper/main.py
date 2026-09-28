from __future__ import annotations
import os
from typing import Any
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from models import ParsedReport, ParseReportRequest
from parsers.culture_parser import parse_cultures
from parsers.lab_parser import infer_report_tags, infer_report_type, parse_lab_parameters
from parsers.pdf_text import decode_report_bytes, detect_non_report_payload, extract_text_from_bytes

APP_VERSION = os.getenv("NIMS_HELPER_VERSION", "0.5.0")
MAX_BODY_BYTES = 25 * 1024 * 1024

app = FastAPI(title="NIMS Results Local Parser", version=APP_VERSION)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["https://nimsts.edu.in","https://www.nimsts.edu.in","http://127.0.0.1:8765","null"],
    allow_origin_regex=r"chrome-extension://.*",
    allow_methods=["GET","POST"],
    allow_headers=["content-type"],
)

@app.middleware("http")
async def body_limit(request: Request, call_next):
    try:
        length = int(request.headers.get("content-length", "0"))
    except ValueError:
        length = 0
    if length > MAX_BODY_BYTES:
        return JSONResponse(status_code=413, content={"ok": False, "error": "request body too large"})
    return await call_next(request)

@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "service": "nims-results-local-parser", "version": APP_VERSION, "local_only": True}

@app.post("/parse-report")
def parse_report(payload: ParseReportRequest) -> dict[str, Any]:
    report_bytes = decode_report_bytes(payload.pdf_base64)
    text = payload.text or ""
    errors: list[str] = []
    if not text:
        text, errors = extract_text_from_bytes(report_bytes)
    non_report_error = detect_non_report_payload(text, payload.content_type)
    if non_report_error:
        return ParsedReport(
            report_id=payload.report_id or "",
            report_name=payload.report_name,
            date_sent=payload.date_sent,
            report_type="other",
            report_tags=["other"],
            errors=[non_report_error],
        ).model_dump()
    tags = infer_report_tags(payload.report_name, text)
    cultures = parse_cultures(text, payload.date_sent) if "culture" in tags else []
    return ParsedReport(
        report_id=payload.report_id or "",
        report_name=payload.report_name,
        date_sent=payload.date_sent,
        report_type=infer_report_type(payload.report_name, text),
        report_tags=tags,
        parameters=parse_lab_parameters(text, payload.date_sent),
        culture=cultures[0] if cultures else None,
        culture_results=cultures,
        raw_text_preview="",
        errors=errors,
    ).model_dump()
