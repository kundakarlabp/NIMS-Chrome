# Local NIMS report parser

Start with:

```bash
pip install -r requirements.txt
uvicorn main:app --host 127.0.0.1 --port 8765
```

Endpoints: `GET /health` and `POST /parse-report`.

There is no remote mode, cache, summarization endpoint, or raw-report persistence.
