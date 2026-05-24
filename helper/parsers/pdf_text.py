from __future__ import annotations

import base64
import io


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

