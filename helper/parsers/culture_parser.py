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

    result.culture_number = first_nonempty(
        clean,
        (
            r"culture\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9\-\/]+)",
            r"lab\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9\-\/]+)",
            r"sample\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9\-\/]+)",
            r"accession\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9\-\/]+)",
        ),
    )
    result.site = first_match(clean, r"(?:site(?: of collection)?|collection site)\s*[:\-]?\s*([A-Za-z /]+?)(?=\s+(?:specimen|sample|result|organism|culture|lab|accession|$))")
    result.specimen = first_match(clean, r"(?:specimen(?: type)?|sample(?: type)?)\s*[:\-]?\s*([A-Za-z /]+?)(?=\s+(?:site|result|organism|culture|lab|accession|$))")

    if any(term in lower for term in ("contaminant", "mixed commensal", "skin flora")):
        result.result_status = "contaminant"
    elif any(term in lower for term in ("no aerobic growth", "no growth", "sterile", "culture negative")):
        result.result_status = "no_growth"
    elif any(term in lower for term in ("pending", "awaited")):
        result.result_status = "pending"
    elif any(term in lower for term in ("growth isolated", "organism isolated", "growth", "isolated", "organism", "culture positive", "positive")):
        result.result_status = "positive"

    organisms: list[str] = []
    for organism in KNOWN_ORGANISMS:
        if organism.lower() in lower:
            organisms.append(organism)

    organism_line = first_match(clean, r"(?:organism(?: isolated)?|isolate)\s*[:\-]?\s*([A-Z][A-Za-z. ]+?)(?=\s+(?:sensitive|susceptible|resistant|intermediate|antibiotic|\bS\b|\bR\b|\bI\b|$))")
    if organism_line and organism_line not in organisms:
        organisms.append(organism_line.strip())
    result.organisms = organisms

    if organisms and result.result_status == "unknown":
        result.result_status = "positive"

    result.sensitivity_summary = {
        "sensitive": extract_antibiotics(text, ("Sensitive", "Susceptible")),
        "resistant": extract_antibiotics(text, ("Resistant",)),
        "intermediate": extract_antibiotics(text, ("Intermediate",)),
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


def first_nonempty(text: str, patterns: tuple[str, ...]) -> str:
    for pattern in patterns:
        value = first_match(text, pattern)
        if value:
            return value
    return ""


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
    values.extend(extract_table_antibiotics(text, headings))
    return dedupe(values)


def extract_table_antibiotics(text: str, headings: tuple[str, ...]) -> list[str]:
    wanted = {h.lower()[0] for h in headings}
    values: list[str] = []
    for line in text.splitlines():
        clean = re.sub(r"\s+", " ", line).strip()
        match = re.match(r"^([A-Za-z][A-Za-z0-9 +/\-]{2,40})\s+(S|R|I|Sensitive|Susceptible|Resistant|Intermediate)\b", clean, flags=re.I)
        if not match:
            match = re.match(r"^(S|R|I|Sensitive|Susceptible|Resistant|Intermediate)\s+([A-Za-z][A-Za-z0-9 +/\-]{2,40})\b", clean, flags=re.I)
            if match:
                status = match.group(1).lower()[0]
                antibiotic = match.group(2).strip()
            else:
                continue
        else:
            antibiotic = match.group(1).strip()
            status = match.group(2).lower()[0]
        if status in wanted:
            values.append(antibiotic)
    return values


def dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        key = value.lower()
        if key not in seen:
            out.append(value)
            seen.add(key)
    return out

