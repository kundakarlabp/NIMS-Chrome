from __future__ import annotations

import re
from dataclasses import dataclass

from models import Parameter, ReportType


@dataclass(frozen=True)
class ParameterPattern:
    canonical: str
    names: tuple[str, ...]
    default_unit: str = ""


PARAMETER_PATTERNS: tuple[ParameterPattern, ...] = (
    ParameterPattern("Hb", ("hemoglobin", "haemoglobin", "hgb", "hb"), "g/dL"),
    ParameterPattern("TLC", ("total leukocyte count", "total leucocyte count", "tlc", "wbc"), "/cumm"),
    ParameterPattern("ANC", ("absolute neutrophil count", "anc"), "/cumm"),
    ParameterPattern("Neutrophils", ("neutrophils", "neutrophil"), "%"),
    ParameterPattern("Lymphocytes", ("lymphocytes", "lymphocyte"), "%"),
    ParameterPattern("Platelets", ("platelet count", "platelets", "plt"), "/cumm"),
    ParameterPattern("Hematocrit", ("hematocrit", "packed cell volume", "pcv"), "%"),
    ParameterPattern("MCV", ("mcv", "mean corpuscular volume"), "fL"),
    ParameterPattern("ESR", ("esr",), "mm/hr"),
    ParameterPattern("CRP", ("c reactive protein", "crp"), "mg/L"),
    ParameterPattern("Procalcitonin", ("procalcitonin", "pct"), "ng/mL"),
    ParameterPattern("Urea", ("urea", "blood urea", "bun"), "mg/dL"),
    ParameterPattern("Creatinine", ("creatinine", "serum creatinine"), "mg/dL"),
    ParameterPattern("Sodium", ("sodium", "na+", "na"), "mmol/L"),
    ParameterPattern("Potassium", ("potassium", "k+", "k"), "mmol/L"),
    ParameterPattern("Chloride", ("chloride", "cl-", "cl"), "mmol/L"),
    ParameterPattern("Bicarbonate", ("bicarbonate", "hco3"), "mmol/L"),
    ParameterPattern("Bilirubin total", ("bilirubin total", "total bilirubin"), "mg/dL"),
    ParameterPattern("Bilirubin direct", ("bilirubin direct", "direct bilirubin"), "mg/dL"),
    ParameterPattern("AST/SGOT", ("ast", "sgot"), "U/L"),
    ParameterPattern("ALT/SGPT", ("alt", "sgpt"), "U/L"),
    ParameterPattern("ALP", ("alkaline phosphatase", "alp"), "U/L"),
    ParameterPattern("Albumin", ("albumin",), "g/dL"),
    ParameterPattern("Total protein", ("total protein",), "g/dL"),
    ParameterPattern("PT", ("prothrombin time", "pt"), "sec"),
    ParameterPattern("INR", ("inr",), ""),
    ParameterPattern("APTT", ("aptt", "activated partial thromboplastin time"), "sec"),
)


NUMBER_RE = r"([-+]?\d[\d,]*(?:\.\d+)?)"
UNIT_RE = r"(mmol/L|mg/dL|g/dL|ng/mL|U/L|sec|fL|/cumm|%|[a-zA-Z][a-zA-Z0-9/%]*(?:/[a-zA-Z0-9]+)?)?"
RANGE_RE = r"(?:\s+(?:ref(?:erence)?\.?\s*range|normal|range)?\s*[:\-]?\s*(\d+(?:\.\d+)?\s*[-–]\s*\d+(?:\.\d+)?))?"


def infer_report_tags(report_name: str, text: str) -> list[str]:
    haystack = f"{report_name}\n{text[:1000]}".lower()
    tags: list[str] = []
    if any(term in haystack for term in ("culture", "sensitivity", "organism", "no growth")):
        tags.append("culture")
    if re.search(r"\b(x-ray|ct|mri|ultrasound|radiology)\b", haystack):
        tags.append("radiology")
    if any(term in haystack for term in ("urea", "creatinine", "renal", "kidney", "rft")):
        tags.append("rft")
    if any(term in haystack for term in ("sodium", "potassium", "chloride", "bicarbonate", "electrolyte")):
        tags.append("electrolytes")
    if any(term in haystack for term in ("bilirubin", "sgot", "sgpt", "albumin", "alkaline phosphatase", "liver", "lft")):
        tags.append("lft")
    if any(term in haystack for term in ("prothrombin", "inr", "aptt", "coagulation")):
        tags.append("coagulation")
    if any(term in haystack for term in ("hemoglobin", "haemoglobin", "platelet", "tlc", "cbc", "complete blood")):
        tags.append("cbc")
    if any(term in haystack for term in ("crp", "c reactive protein", "procalcitonin")):
        tags.append("inflammatory")
    return tags or ["other"]


def infer_report_type(report_name: str, text: str) -> ReportType:
    for tag in infer_report_tags(report_name, text):
        if tag != "inflammatory":
            return tag  # type: ignore[return-value]
    return "other"


def parse_lab_parameters(text: str, date_sent: str = "") -> list[Parameter]:
    normalized = normalize_text(text)
    search_text = normalized + "\n" + line_window_text(text)
    found: dict[str, Parameter] = {}

    for pattern in PARAMETER_PATTERNS:
        for name in pattern.names:
            label = re.escape(name).replace(r"\ ", r"\s+")
            regex = re.compile(
                rf"(?<![a-z0-9])({label})(?![a-z0-9])(?:\s+(?:result|value))?\s*[:=\-]?\s*{NUMBER_RE}(?:\s+(?:unit))?\s*{UNIT_RE}{RANGE_RE}",
                flags=re.IGNORECASE,
            )
            match = regex.search(search_text)
            if not match:
                continue
            raw_name = match.group(1).strip()
            value = match.group(2).replace(",", "")
            unit = (match.group(3) or pattern.default_unit).strip()
            reference_range = (match.group(4) or "").strip()
            if not reference_range:
                reference_range = find_reference_range(search_text[match.end() : match.end() + 80])
            found[pattern.canonical] = Parameter(
                name=raw_name,
                canonical_name=pattern.canonical,
                value=value,
                unit=unit,
                reference_range=reference_range,
                abnormal_flag=abnormal_flag(value, reference_range),
                date_sent=date_sent,
            )
            break

    return list(found.values())


def normalize_text(text: str) -> str:
    lines = [re.sub(r"\s+", " ", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


def line_window_text(text: str) -> str:
    lines = [re.sub(r"\s+", " ", line).strip() for line in text.splitlines()]
    lines = [line for line in lines if line]
    windows = []
    for idx in range(len(lines)):
        windows.append(" ".join(lines[idx : idx + 4]))
    return "\n".join(windows)


def abnormal_flag(value: str, reference_range: str) -> str:
    if not reference_range:
        return "unknown"
    try:
        numeric = float(value.replace(",", ""))
        low_s, high_s = re.split(r"[-–]", reference_range, maxsplit=1)
        low = float(low_s.strip())
        high = float(high_s.strip())
    except Exception:
        return "unknown"
    if numeric < low:
        return "low"
    if numeric > high:
        return "high"
    return "normal"


def find_reference_range(tail: str) -> str:
    match = re.search(r"(?:ref(?:erence)?\.?\s*range|normal|range)?\s*[:\-]?\s*(\d+(?:\.\d+)?\s*[-–]\s*\d+(?:\.\d+)?)", tail, flags=re.I)
    return match.group(1).strip() if match else ""

