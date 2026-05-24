from __future__ import annotations

import re

from models import CultureResult


KNOWN_ORGANISMS = (
    "Klebsiella pneumoniae",
    "Escherichia coli",
    "E. coli",
    "Pseudomonas aeruginosa",
    "Acinetobacter baumannii",
    "Staphylococcus aureus",
    "Enterococcus faecalis",
    "Candida albicans",
    "Candida tropicalis",
)


def parse_culture(text: str) -> CultureResult:
    clean = re.sub(r"\s+", " ", text).strip()
    lower = clean.lower()
    result = CultureResult()

    result.culture_number = first_match(clean, r"(?:culture|lab)\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9\-\/]+)")
    result.site = first_match(clean, r"(?:site(?: of collection)?|collection site)\s*[:\-]?\s*([A-Za-z /]+?)(?=\s+(?:specimen|result|organism|culture|lab|$))")
    result.specimen = first_match(clean, r"(?:specimen(?: type)?|sample)\s*[:\-]?\s*([A-Za-z /]+?)(?=\s+(?:site|result|organism|culture|lab|$))")

    if any(term in lower for term in ("contaminant", "mixed commensal", "skin flora")):
        result.result_status = "contaminant"
    elif any(term in lower for term in ("no aerobic growth", "no growth", "sterile", "culture negative")):
        result.result_status = "no_growth"
    elif any(term in lower for term in ("pending", "awaited")):
        result.result_status = "pending"
    elif any(term in lower for term in ("growth", "isolated", "organism", "culture positive")):
        result.result_status = "positive"

    organisms: list[str] = []
    for organism in KNOWN_ORGANISMS:
        if organism.lower() in lower:
            organisms.append(organism)

    organism_line = first_match(clean, r"(?:organism(?: isolated)?|isolate)\s*[:\-]?\s*([A-Z][A-Za-z. ]+?)(?=\s+(?:sensitive|susceptible|resistant|intermediate|antibiotic|$))")
    if organism_line and organism_line not in organisms:
        organisms.append(organism_line.strip())
    result.organisms = organisms

    if organisms and result.result_status == "unknown":
        result.result_status = "positive"

    result.sensitivity_summary = {
        "sensitive": extract_antibiotics(clean, ("Sensitive", "Susceptible")),
        "resistant": extract_antibiotics(clean, ("Resistant",)),
        "intermediate": extract_antibiotics(clean, ("Intermediate",)),
    }

    if "preliminary" in lower:
        result.report_status = "preliminary"
    elif "48 hour" in lower or "48-hour" in lower:
        result.report_status = "48_hour"
    elif "final" in lower:
        result.report_status = "final"

    return result


def first_match(text: str, pattern: str) -> str:
    match = re.search(pattern, text, flags=re.IGNORECASE)
    return match.group(1).strip(" :;-") if match else ""


def extract_antibiotics(text: str, headings: tuple[str, ...]) -> list[str]:
    values: list[str] = []
    heading_group = "|".join(re.escape(h) for h in headings)
    pattern = re.compile(
        rf"(?:{heading_group})\s*[:\-]?\s*([A-Za-z0-9, /+\-]+?)(?=\s+(?:Sensitive|Susceptible|Resistant|Intermediate|Final|Preliminary|$))",
        flags=re.IGNORECASE,
    )
    for match in pattern.finditer(text):
        chunk = match.group(1)
        for item in re.split(r",|;|/", chunk):
            item = item.strip(" .")
            if item and len(item) > 2 and item.lower() not in {"and", "nil", "none"}:
                values.append(item)
    return dedupe(values)


def dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        key = value.lower()
        if key not in seen:
            out.append(value)
            seen.add(key)
    return out

