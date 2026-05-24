# NIMS Fast Summary Helper

Local FastAPI parser service for the NIMS Fast Summary Chrome extension.

## Run

```powershell
cd helper
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8765
```

Check:

```powershell
curl.exe http://127.0.0.1:8765/health
```

Expected response:

```json
{"ok": true}
```

## Endpoints

- `GET /health`
- `POST /parse-report`
- `POST /summarize`
- `POST /clear-cache`

Raw PDFs are parsed in memory only. The cache stores parsed JSON in `cache.db`; it does not store raw PDFs.

## OCR and AI

OCR is disabled by default because it is slower and may require extra local dependencies. AI interpretation is not required for the MVP. If an API key is configured in the environment later, only de-identified structured JSON should be sent outside the machine.

