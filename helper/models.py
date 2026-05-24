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
    raw_text_preview: str = ""
    errors: list[str] = Field(default_factory=list)
    cached: bool = False


class SummarizeRequest(BaseModel):
    mode: Literal["fast", "cultures_only", "full"] = "fast"
    reports: list[ParsedReport | dict[str, Any]] = Field(default_factory=list)

