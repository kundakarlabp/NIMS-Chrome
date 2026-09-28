# AGENTS.md

## Canonical product

Maintain one desktop NIMS Results Dashboard workflow:

Dashboard → authenticated Chrome/NIMS session → REST reportList first → existing browser bridge fallback → deterministic parser → canonical result bundle.

## Preserve

- manual NIMS login and CAPTCHA
- dashboardBridge contract
- nimsSessionBridge session detection and CR submission
- nimsRestApi REST-first adapter
- navigationCore
- contentUtils/contentScript fallback processor
- helper parser
- fail-closed identity verification
- explicit source/fallback events

## Do not reintroduce

- Android/mobile runtime
- mobile or ChatGPT relay
- side panel
- manual-analysis UI
- Chrome Web Store publication workflow
- duplicated navigation engines
- CAPTCHA solving or credential capture

The old content processor is retained only because the working authenticated browser fallback depends on it. Its visible toolbar is disabled.

## Required validation

```bash
npm ci
npm test
pip install -r helper/requirements-dev.txt
PYTHONPATH=helper python -m pytest -q tests/test_parsers.py
```
