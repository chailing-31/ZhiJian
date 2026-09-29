"""Run against a started backend: python scripts/smoke_batch.py [base_url]."""
import json
import sys
import urllib.error
import urllib.request

base = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")

def request(path, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(base + path, data=body, headers={"Content-Type": "application/json"} if body else {}, method="POST" if body else "GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, None

assert request("/health") == (200, {"status": "ok"})
status, batches = request("/batches")
assert status == 200
batch = next(item for item in batches if item["batch_code"] == "APPLE-2026-001")
status, detail = request("/batches/" + str(batch["batch_id"]))
assert status == 200 and detail["batch_code"] == batch["batch_code"]
status, trace = request("/trace/APPLE-2026-001")
assert status == 200 and trace["batch_code"] == batch["batch_code"]
assert all(set(e) == {"event_type", "event_time", "summary", "source"} for e in trace["events"])
assert "supplier" not in trace
assert request("/batches", {"batch_code": "APPLE-2026-001", "product": "苹果"})[0] == 409
assert request("/trace/DOES-NOT-EXIST")[0] == 404
print("Batch flow passed: list, detail, public trace, duplicate and missing batch")
