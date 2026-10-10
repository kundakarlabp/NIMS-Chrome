from __future__ import annotations

import re

from models import CultureResult


KNOWN_ORGANISMS = (
    "Gram negative bacilli",
    "Gram positive cocci",
    "Gram positive bacilli",
    "Gram negative cocci",
    "Escherichia coli",
    "Klebsiella pneumoniae",
    "Klebsiella species",
    "Pseudomonas aeruginosa",
    "Acinetobacter baumannii",
    "Acinetobacter species",
    "Staphylococcus aureus",
    "Coagulase negative Staphylococcus",
    "Enterococcus species",
    "Enterococcus faecalis",
    "Enterococcus faecium",
    "Streptococcus species",
    "Candida species",
    "Candida albicans",
    "Candida tropicalis",
    "Proteus species",
    "Morganella morganii",
    "Citrobacter species",
    "Enterobacter species",
    "Salmonella species",
    "Burkholderia cepacia",
    "Stenotrophomonas maltophilia",
    "E. coli",
)

ANTIBIOTICS = (
    "Amikacin",
    "Gentamicin",
    "Tobramycin",
    "Ceftriaxone",
    "Cefotaxime",
    "Ceftazidime",
    "Cefepime",
    "Piperacillin-tazobactam",
    "Piperacillin tazobactam",
    "Amoxicillin-clavulanate",
    "Amoxicillin clavulanate",
    "Ampicillin",
    "Ciprofloxacin",
    "Levofloxacin",
    "Meropenem",
    "Imipenem",
    "Ertapenem",
    "Colistin",
    "Tigecycline",
    "Cotrimoxazole",
    "Nitrofurantoin",
    "Vancomycin",
    "Linezolid",
    "Teicoplanin",
    "Daptomycin",
    "Clindamycin",
    "Erythromycin",
    "Oxacillin",
    "Cefoxitin",
    "Fluconazole",
    "Voriconazole",
    "Amphotericin B",
    "Caspofungin",
)

GROWTH_WORDS = ("scanty", "light", "moderate", "heavy", "significant", "pure", "mixed")

SAMPLE_NORMALIZATION = {
    "blood": "Blood",
    "sputum": "Sputum",
    "urine": "Urine",
    "pus": "Pus",
    "csf": "CSF",
    "bal": "BAL",
    "et secretions": "Endotracheal secretion",
    "endotracheal aspirate": "Endotracheal aspirate",
    "tracheal aspirate": "Tracheal aspirate",
    "wound swab": "Wound swab",
    "tissue": "Tissue",
    "fluid": "Fluid",
}


def parse_culture(text: str) -> CultureResult:
    cultures = parse_cultures(text)
    return cultures[0] if cultures else CultureResult()


def parse_cultures(text: str, date_sent: str = "") -> list[CultureResult]:
    common = extract_common_fields(text, date_sent)
    sections = split_culture_sections(text)
    if not sections:
        sections = [("", text)]
    cultures = [parse_section(title, body, common) for title, body in sections]
    return cultures


def extract_common_fields(text: str, date_sent: str) -> dict[str, str]:
    sample = normalize_sample(
        first_nonempty(
            text,
            (
                r"Sample\s*Processed\s*:\s*([A-Za-z ]+)",
                r"Sample\s*Processed\s+([A-Za-z ]+)",
            ),
        )
    )
    specimen_no = first_nonempty(
        text,
        (
            r"Specimen\s*/\s*([A-Za-z0-9/-]+)",
            r"Specimen\s*No\.?\s*:\s*([A-Za-z0-9/-]+)",
        ),
    )
    culture_no = first_nonempty(
        text,
        (
            r"Lab\s*/?\s*Study\s*No\.?\s*:\s*([A-Za-z0-9/-]+)",
            r"Lab\s*Study\s*No\.?\s*:\s*([A-Za-z0-9/-]+)",
            r"Lab\s*No\.?\s*:\s*([A-Za-z0-9/-]+)",
            r"culture\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9/-]+)",
            r"accession\s*(?:no|number|#)\s*[:\-]?\s*([A-Za-z0-9/-]+)",
        ),
    )
    legacy_site = first_match(
        text,
        r"(?:site(?: of collection)?|collection site)\s*[:\-]?\s*([A-Za-z /]+?)(?=\s+(?:specimen|sample|result|organism|culture|lab|accession|$))",
    )
    legacy_specimen = first_match(
        text,
        r"(?:specimen(?: type)?|sample(?: type)?)\s*[:\-]?\s*([A-Za-z /]+?)(?=\s+(?:site|result|organism|culture|lab|accession|$))",
    )
    if legacy_specimen.lower() == "any other" and sample:
        legacy_specimen = sample
    return {
        "date_sent": date_sent,
        "culture_no": culture_no,
        "specimen_no": specimen_no,
        "sample_processed": sample,
        "site_specimen": sample or legacy_site or legacy_specimen,
        "requisition_date": extract_date_field(text, r"Requisition\s*Date"),
        "collection_date": extract_date_field(text, r"Coll\.?\s*/?\s*Study\s*Date"),
        "reporting_date": extract_date_field(text, r"Reporting\s*Date"),
        "site": legacy_site or sample,
        "specimen": legacy_specimen or sample,
    }


def split_culture_sections(text: str) -> list[tuple[str, str]]:
    heading = re.compile(
        r"(?im)^\s*((?:Fan\s+)?Blood\s+Culture\s*-\s*(?:First|Second)\s+Bottle\s+of\s+(?:first|second|1st|2nd)\s+Set(?:\s*\([^)]*\))?)\s*$"
    )
    matches = list(heading.finditer(text))
    if len(matches) <= 1:
        return []
    sections: list[tuple[str, str]] = []
    for index, match in enumerate(matches):
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        sections.append((match.group(1).strip(), text[start:end]))
    return sections


def parse_section(title: str, section: str, common: dict[str, str]) -> CultureResult:
    full = f"{title}\n{section}".strip()
    clean = re.sub(r"\s+", " ", full).strip()
    lower = clean.lower()
    susceptible, resistant, intermediate = extract_susceptibility(section)
    growth_quantity, organism = extract_growth_and_organism(clean)
    result = infer_result(lower, organism)
    comment = extract_comment(lower)
    culture_type = infer_culture_type(clean, common.get("site_specimen", ""))
    report_status = infer_report_status(title, clean)

    culture = CultureResult(
        date_sent=common.get("date_sent", ""),
        requisition_date=common.get("requisition_date", ""),
        collection_date=common.get("collection_date", ""),
        reporting_date=common.get("reporting_date", ""),
        culture_no=common.get("culture_no", ""),
        specimen_no=common.get("specimen_no", ""),
        sample_processed=common.get("sample_processed", ""),
        site_specimen=common.get("site_specimen", ""),
        culture_type=culture_type,
        bottle_set=detect_bottle_set(title or clean),
        report_status=report_status,
        result=result,
        growth_quantity=growth_quantity,
        organism=organism,
        comment=comment,
        sensitivity_summary={
            "sensitive": susceptible,
            "resistant": resistant,
            "intermediate": intermediate,
        },
        susceptible_antibiotics=susceptible,
        resistant_antibiotics=resistant,
        intermediate_antibiotics=intermediate,
        raw_evidence_short=short_evidence(clean),
        culture_number=common.get("culture_no", ""),
        site=common.get("site", ""),
        specimen=common.get("specimen", ""),
        result_status=result if result != "possible_contaminant" else "contaminant",
        organisms=[organism] if organism else [],
    )
    return culture


def infer_result(lower: str, organism: str) -> str:
    if re.search(r"culture\s+shows\s+no\s+growth|no\s+growth\s+aerobically|no\s+aerobic\s+growth|no\s+growth\s+after\s+incubation", lower):
        return "no_growth"
    if "pending" in lower or "awaited" in lower:
        return "pending"
    if organism or re.search(r"growth\s+of|organism\s+isolated|isolated|yielding|culture positive", lower):
        return "positive"
    if "mixed bacterial flora" in lower or "skin contaminant" in lower:
        return "possible_contaminant"
    return "unknown"


def extract_growth_and_organism(text: str) -> tuple[str, str]:
    patterns = (
        r"CULTURE\s+SHOWS\s+(?:(SCANTY|LIGHT|MODERATE|HEAVY|SIGNIFICANT|PURE|MIXED)\s+)?GROWTH\s+OF\s+(.+?)(?:\.|\n|$)",
        r"(?:(SCANTY|LIGHT|MODERATE|HEAVY|SIGNIFICANT|PURE|MIXED)\s+)?GROWTH\s+OF\s+(.+?)(?:\.|\n|$)",
        r"ORGANISM\s+ISOLATED\s*:?\s*(.+?)(?:\.|\n|$)",
        r"ISOLATED\s+(.+?)(?:\.|\n|$)",
        r"YIELDING\s+(.+?)(?:\.|\n|$)",
    )
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.IGNORECASE)
        if not match:
            continue
        if len(match.groups()) == 2:
            growth = (match.group(1) or "").lower()
            candidate = match.group(2)
        else:
            growth = ""
            candidate = match.group(1)
        organism = clean_organism(candidate)
        if organism:
            return growth, organism
    for organism in KNOWN_ORGANISMS:
        if re.search(rf"\b{re.escape(organism)}\b", text, flags=re.IGNORECASE):
            return "", organism
    return "", ""


def clean_organism(value: str) -> str:
    candidate = re.sub(
        r"\b(?:COLONIZATION|CONTAMINATION|PLEASE|REPEAT|IF|NECESSARY|SENSITIVE|RESISTANT|INTERMEDIATE|ANTIBIOTIC).*$",
        "",
        value.strip(" :;,."),
        flags=re.IGNORECASE,
    ).strip(" :;,.?")
    for organism in KNOWN_ORGANISMS:
        if re.search(rf"\b{re.escape(organism)}\b", candidate, flags=re.IGNORECASE):
            return organism
    if not candidate:
        return ""
    return title_organism(candidate)


def title_organism(value: str) -> str:
    words = value.lower().split()
    if not words:
        return ""
    if words[0] in {"gram", "coagulase"}:
        return " ".join(words).capitalize()
    return " ".join([words[0].capitalize(), *words[1:]])


def extract_comment(lower: str) -> str:
    comments: list[str] = []
    if "colonization or contamination" in lower:
        comments.append("Possible colonization/contamination")
    elif "probable contaminant" in lower or "skin contaminant" in lower:
        comments.append("Possible contaminant")
    elif "mixed growth" in lower or "mixed bacterial flora" in lower:
        comments.append("Mixed growth reported")
    if "repeat if necessary" in lower:
        comments.append("repeat if necessary")
    if "final report after 5 days of incubation" in lower:
        comments.append("Final report after 5 days of incubation")
    if "36to 48 hours" in lower or "36 to 48 hours" in lower:
        comments.append("Incubated about 36 to 48 hours")
    if not comments:
        return ""
    sentence = "; ".join(dedupe(comments))
    return sentence[0].upper() + sentence[1:] + "."


def infer_culture_type(text: str, site: str) -> str:
    lower = text.lower()
    if "fan blood culture" in lower:
        return "Fan blood culture"
    if "blood culture" in lower:
        return "Blood culture"
    if "aerobic culture" in lower:
        return "Aerobic culture"
    if "anaerobic culture" in lower:
        return "Anaerobic culture"
    if "bacterology urine" in lower or "urine culture" in lower or site.lower() == "urine":
        return "Urine culture"
    for sample, label in (
        ("sputum", "Sputum culture"),
        ("fungal", "Fungal culture"),
        ("csf", "CSF culture"),
        ("pus", "Pus culture"),
        ("wound swab", "Wound swab culture"),
    ):
        if sample in lower or site.lower() == sample:
            return label
    return "Culture"


def detect_bottle_set(text: str) -> str:
    match = re.search(r"(First|Second)\s+Bottle\s+of\s+(first|second|1st|2nd)\s+Set", text, flags=re.IGNORECASE)
    if not match:
        return ""
    bottle = "1" if match.group(1).lower() == "first" else "2"
    set_no = "1" if match.group(2).lower() in {"first", "1st"} else "2"
    return f"set_{set_no}_bottle_{bottle}"


def infer_report_status(title: str, text: str) -> str:
    title_lower = title.lower()
    if re.search(r"48\s*(?:hrs?|hours?)", title_lower):
        return "48_hour"
    if "final" in title_lower:
        return "final"
    without_instruction = re.sub(r"final report after 5 days of incubation", "", text, flags=re.IGNORECASE)
    if re.search(r"\bfinal\s+report\b", without_instruction, flags=re.IGNORECASE):
        return "final"
    if re.search(r"48\s*(?:hrs?|hours?)", text, flags=re.IGNORECASE):
        return "48_hour"
    if "preliminary" in text.lower():
        return "preliminary"
    return "unknown"


def extract_susceptibility(text: str) -> tuple[list[str], list[str], list[str]]:
    buckets = {"s": [], "r": [], "i": []}
    antibiotic_pattern = "|".join(re.escape(item) for item in sorted(ANTIBIOTICS, key=len, reverse=True))
    for line in text.splitlines():
        clean = re.sub(r"\s+", " ", line).strip(" .")
        if not clean:
            continue
        for match in re.finditer(
            rf"\b({antibiotic_pattern})\b\s*[:\-]?\s*(S|R|I|Sensitive|Susceptible|Resistant|Intermediate)\b",
            clean,
            flags=re.IGNORECASE,
        ):
            buckets[status_key(match.group(2))].append(normalize_antibiotic(match.group(1)))
        for match in re.finditer(
            rf"\b(S|R|I|Sensitive|Susceptible|Resistant|Intermediate)\b\s*[:\-]?\s*({antibiotic_pattern})\b",
            clean,
            flags=re.IGNORECASE,
        ):
            buckets[status_key(match.group(1))].append(normalize_antibiotic(match.group(2)))
    return dedupe(buckets["s"]), dedupe(buckets["r"]), dedupe(buckets["i"])


def status_key(value: str) -> str:
    return value.lower()[0]


def normalize_antibiotic(value: str) -> str:
    compact = re.sub(r"\s+", " ", value.strip())
    for antibiotic in ANTIBIOTICS:
        if antibiotic.lower() == compact.lower():
            return antibiotic.replace(" tazobactam", "-tazobactam").replace(" clavulanate", "-clavulanate")
    return compact.title()


def format_sensitivity_summary(sensitive: list[str], resistant: list[str], intermediate: list[str]) -> str:
    parts = []
    if sensitive:
        parts.append(f"Sensitive: {', '.join(sensitive)}")
    if resistant:
        parts.append(f"Resistant: {', '.join(resistant)}")
    if intermediate:
        parts.append(f"Intermediate: {', '.join(intermediate)}")
    return "; ".join(parts) if parts else "No susceptibility table found"


def normalize_sample(value: str) -> str:
    clean = re.sub(r"\s+", " ", value.strip(" :;,."))
    if not clean:
        return ""
    key = clean.lower()
    return SAMPLE_NORMALIZATION.get(key, clean.title())


def extract_date_field(text: str, label_pattern: str) -> str:
    return first_match(text, rf"{label_pattern}\s*:?\s*([0-9]{{1,2}}-[A-Za-z]{{3}}-[0-9]{{2,4}}(?:\s+[0-9]{{1,2}}:[0-9]{{2}})?)")


def first_match(text: str, pattern: str) -> str:
    match = re.search(pattern, text, flags=re.IGNORECASE)
    return match.group(1).strip(" :;-") if match else ""


def first_nonempty(text: str, patterns: tuple[str, ...]) -> str:
    for pattern in patterns:
        value = first_match(text, pattern)
        if value:
            return value
    return ""


def short_evidence(text: str) -> str:
    evidence = first_nonempty(
        text,
        (
            r"(CULTURE\s+SHOWS\s+[^.]+\.?)",
            r"(NO\s+GROWTH\s+[^.]+\.?)",
            r"(ORGANISM\s+ISOLATED\s*:?\s*[^.]+\.?)",
        ),
    )
    return evidence[:220]


def dedupe(values: list[str]) -> list[str]:
    seen: set[str] = set()
    out: list[str] = []
    for value in values:
        key = value.lower()
        if key and key not in seen:
            out.append(value)
            seen.add(key)
    return out
