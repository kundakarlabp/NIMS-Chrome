from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field


ReportType = Literal[
    "cbc",
    "rft",
    "lft",
    "electrolytes",
    "coagulation",
    "culture",
    "radiology",
    "other",
]


class ParseReportRequest(BaseModel):
    report_id: str | None = None
    report_name: str = ""
    date_sent: str = ""
    source_url: str = ""
    pdf_base64: str = ""
    text: str | None = None
    content_type: str = ""


class Parameter(BaseModel):
    name: str
    canonical_name: str
    value: str
    unit: str = ""
    reference_range: str = ""
    abnormal_flag: Literal["low", "high", "normal", "unknown"] = "unknown"
    date_sent: str = ""


class CultureResult(BaseModel):
    date_sent: str = ""
    requisition_date: str = ""
    collection_date: str = ""
    reporting_date: str = ""
    culture_no: str = ""
    specimen_no: str = ""
    sample_processed: str = ""
    site_specimen: str = ""
    culture_type: str = ""
    bottle_set: str = ""
    result: Literal[
        "positive", "negative", "no_growth", "pending", "contaminant", "possible_contaminant", "unknown"
    ] = "unknown"
    growth_quantity: str = ""
    organism: str = ""
    comment: str = ""
    susceptible_antibiotics: list[str] = Field(default_factory=list)
    resistant_antibiotics: list[str] = Field(default_factory=list)
    intermediate_antibiotics: list[str] = Field(default_factory=list)
    raw_evidence_short: str = ""
    culture_parser_version: int = 2
    culture_number: str = ""
    site: str = ""
    specimen: str = ""
    result_status: Literal[
        "positive", "negative", "no_growth", "pending", "contaminant", "unknown"
    ] = "unknown"
    organisms: list[str] = Field(default_factory=list)
    sensitivity_summary: dict[str, list[str]] = Field(
        default_factory=lambda: {"sensitive": [], "resistant": [], "intermediate": []}
    )
    report_status: Literal["preliminary", "final", "48_hour", "unknown"] = "unknown"


class ParsedReport(BaseModel):
    report_id: str = ""
    report_name: str = ""
    date_sent: str = ""
    report_type: ReportType = "other"
    report_tags: list[str] = Field(default_factory=list)
    parameters: list[Parameter] = Field(default_factory=list)
    culture: CultureResult | None = None
    culture_results: list[CultureResult] = Field(default_factory=list)
    raw_text_preview: str = ""
    errors: list[str] = Field(default_factory=list)
    cached: bool = False


class SummarizeRequest(BaseModel):
    mode: Literal["fast", "cultures_only", "full"] = "fast"
    reports: list[ParsedReport | dict[str, Any]] = Field(default_factory=list)


class CacheLookupItem(BaseModel):
    report_key: str
    report_name: str = ""
    date_sent: str = ""


class CacheLookupRequest(BaseModel):
    reports: list[CacheLookupItem] = Field(default_factory=list)

