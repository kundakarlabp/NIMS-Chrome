from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKILLS_ROOT = ROOT / ".agents" / "skills"
EXPECTED_SKILLS = {
    "clinical-software-safety",
    "robust-repo-change",
    "session-worklog",
}


def _parse_frontmatter(text: str) -> dict[str, str]:
    assert text.startswith("---\n"), "missing opening YAML delimiter"
    raw, _body = text[4:].split("\n---\n", 1)
    metadata: dict[str, str] = {}
    for line in raw.splitlines():
        if not line.strip():
            continue
        key, value = line.split(":", 1)
        metadata[key.strip()] = value.strip()
    return metadata


def test_expected_agent_skill_catalog_is_present() -> None:
    discovered = {
        path.name
        for path in SKILLS_ROOT.iterdir()
        if path.is_dir() and (path / "SKILL.md").is_file()
    }
    assert discovered == EXPECTED_SKILLS


def test_agent_skills_have_activation_metadata_and_sections() -> None:
    for name in sorted(EXPECTED_SKILLS):
        skill_file = SKILLS_ROOT / name / "SKILL.md"
        text = skill_file.read_text(encoding="utf-8")
        metadata = _parse_frontmatter(text)
        assert metadata.get("name") == name
        assert len(metadata.get("description", "")) >= 40
        assert re.search(r"^## Purpose\s*$", text, re.MULTILINE)
        assert re.search(r"^## Workflow\s*$", text, re.MULTILINE)
        assert text.endswith("\n")


def test_agent_skill_provenance_is_recorded() -> None:
    source = (SKILLS_ROOT / "SOURCE.md").read_text(encoding="utf-8")
    assert "kundakarlabp/dr-bhanu-prasad" in source
    assert "c92ac30e6c2e2c7998fd8ebf2669f90b117151a3" in source
