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
    ParameterPattern("Hb", ("hemoglobin", "haemoglobin", "hb"), "g/dL"),
    ParameterPattern("TLC", ("total leukocyte count", "total leucocyte count", "tlc", "wbc"), "/cumm"),
    ParameterPattern("Neutrophils", ("neutrophils", "neutrophil"), "%"),
    ParameterPattern("Lymphocytes", ("lymphocytes", "lymphocyte"), "%"),
    ParameterPattern("Platelets", ("platelet count", "platelets"), "/cumm"),
    ParameterPattern("Hematocrit", ("hematocrit", "packed cell volume", "pcv"), "%"),
    ParameterPattern("MCV", ("mcv", "mean corpuscular volume"), "fL"),
    ParameterPattern("ESR", ("esr",), "mm/hr"),
    ParameterPattern("CRP", ("c reactive protein", "crp"), "mg/L"),
    ParameterPattern("Procalcitonin", ("procalcitonin", "pct"), "ng/mL"),
    ParameterPattern("Urea", ("urea", "blood urea"), "mg/dL"),
    ParameterPattern("Creatinine", ("creatinine", "serum creatinine"), "mg/dL"),
    ParameterPattern("Sodium", ("sodium", "na+", "na "), "mmol/L"),
    ParameterPattern("Potassium", ("potassium", "k+", "k "), "mmol/L"),
    ParameterPattern("Chloride", ("chloride", "cl-", "cl "), "mmol/L"),
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
UNIT_RE = r"([a-zA-Z/%]+(?:/[a-zA-Z]+)?|mmol/L|mg/dL|g/dL|ng/mL|U/L|sec|fL)?"
RANGE_RE = r"(?:\s+(?:ref(?:erence)?\.?\s*range|normal|range)?\s*[:\-]?\s*(\d+(?:\.\d+)?\s*[-–]\s*\d+(?:\.\d+)?))?"


def infer_report_type(report_name: str, text: str) -> ReportType:
    haystack = f"{report_name}\n{text[:1000]}".lower()
    if any(term in haystack for term in ("culture", "sensitivity", "organism", "no growth")):
        return "culture"
    if any(term in haystack for term in ("x-ray", "ct ", "mri", "ultrasound", "radiology")):
        return "radiology"
    if any(term in haystack for term in ("prothrombin", "inr", "aptt", "coagulation")):
        return "coagulation"
    if any(term in haystack for term in ("bilirubin", "sgot", "sgpt", "albumin", "alkaline phosphatase", "liver")):
        return "lft"
    if any(term in haystack for term in ("sodium", "potassium", "chloride", "bicarbonate", "electrolyte")):
        return "electrolytes"
    if any(term in haystack for term in ("urea", "creatinine", "renal", "kidney", "rft")):
        return "rft"
    if any(term in haystack for term in ("hemoglobin", "haemoglobin", "platelet", "tlc", "cbc", "complete blood")):
        return "cbc"
    return "other"


def parse_lab_parameters(text: str, date_sent: str = "") -> list[Parameter]:
    normalized = normalize_text(text)
    found: dict[str, Parameter] = {}

    for pattern in PARAMETER_PATTERNS:
        for name in pattern.names:
            label = re.escape(name).replace(r"\ ", r"\s+")
            regex = re.compile(
                rf"(?<![a-z0-9])({label})(?![a-z0-9])\s*[:=\-]?\s*{NUMBER_RE}\s*{UNIT_RE}{RANGE_RE}",
                flags=re.IGNORECASE,
            )
            match = regex.search(normalized)
            if not match:
                continue
            raw_name = match.group(1).strip()
            value = match.group(2).replace(",", "")
            unit = (match.group(3) or pattern.default_unit).strip()
            reference_range = (match.group(4) or "").strip()
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

