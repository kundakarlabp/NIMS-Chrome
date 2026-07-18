# NIMS culture mapping

NIMS bacteriology PDFs are sectioned free-text reports rather than pre-normalized antibiogram tables. Android parsing therefore treats each report as one or more **culture episodes**.

An episode may represent:

- a blood-culture bottle and set;
- isolate 1, isolate 2, or additional isolates from one specimen;
- a Gram-stain-only observation;
- a 48-hour preliminary/interim report;
- a final report for the same bottle or specimen.

Preliminary and final observations are retained separately. They are not deduplicated merely because the specimen, bottle, or organism is the same.

Structured output may include:

- `lab_study_number`
- `collection_date`
- `reporting_date`
- `report_stage`
- `specimen`
- `site`
- `bottle_name`, `set_number`, `bottle_number`
- `isolate_number`
- `gram_stain`
- `organism`, `organism_raw`
- `status`
- `susceptibility[]` with antibiotic, interpretation and optional MIC
- resistance markers and laboratory comments

The parser recognizes the NIMS section headings `CULTURE REPORT`, `SENSITIVITY REPORT`, `INTERMEDIATE REPORT`, `RESISTANCE REPORT`, and `STAINING`. `NIL` is preserved as an explicitly empty section. Source report text remains available for verification.
