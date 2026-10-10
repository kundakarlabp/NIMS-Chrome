from __future__ import annotations

import base64
import io
import re


def decode_report_bytes(pdf_base64: str) -> bytes:
    if not pdf_base64:
        return b""
    if "," in pdf_base64 and pdf_base64.strip().startswith("data:"):
        pdf_base64 = pdf_base64.split(",", 1)[1]
    return base64.b64decode(pdf_base64)


def extract_text_from_bytes(data: bytes) -> tuple[str, list[str]]:
    errors: list[str] = []
    if not data:
        return "", ["empty report payload"]

    try:
        import fitz

        with fitz.open(stream=data, filetype="pdf") as doc:
            text = "\n".join(page.get_text("text") for page in doc)
        if len(text.strip()) >= 40:
            return text, errors
        errors.append("PyMuPDF extracted very little text")
    except Exception as exc:  # pragma: no cover - depends on PDF engine internals
        errors.append(f"PyMuPDF failed: {exc}")

    try:
        import pdfplumber

        with pdfplumber.open(io.BytesIO(data)) as pdf:
            text = "\n".join(page.extract_text() or "" for page in pdf.pages)
        if len(text.strip()) >= 40:
            return text, errors
        errors.append("pdfplumber extracted very little text")
    except Exception as exc:  # pragma: no cover - depends on PDF engine internals
        errors.append(f"pdfplumber failed: {exc}")

    try:
        text = data.decode("utf-8")
        if text.strip():
            return text, errors
    except UnicodeDecodeError:
        pass

    errors.append("OCR disabled; unable to extract usable text")
    return "", errors


def detect_non_report_payload(text: str, content_type: str = "") -> str:
    head = (text or "")[:5000].lower()
    ctype = (content_type or "").lower()
    if "text/html" in ctype or "<html" in head or "<!doctype html" in head:
        if re.search(r"\b(login|session expired|session has expired|authentication|captcha|otp|sign in|password)\b", head):
            return "session expired or report fetch failed"
        if not re.search(r"\b(hemoglobin|creatinine|culture|bilirubin|platelet|sodium|potassium|report)\b", head):
            return "HTML response is not a recognizable report"
    return ""

