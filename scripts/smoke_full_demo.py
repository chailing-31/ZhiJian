r"""Read-only full Demo smoke test for ZhiJian.

Usage:
    python scripts\smoke_full_demo.py

Optional:
    python scripts\smoke_full_demo.py --backend http://127.0.0.1:8080 \
        --ai http://127.0.0.1:8001 --frontend http://127.0.0.1:5173 \
        --batch-code APPLE-2026-001

GET-only: no batch creation, image upload, review update, or DB writes.
"""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable


@dataclass
class Result:
    level: str
    name: str
    detail: str = ""


class Smoke:
    def __init__(self, backend: str, ai: str, frontend: str, batch_code: str, timeout: float):
        self.backend = backend.rstrip("/")
        self.ai = ai.rstrip("/")
        self.frontend = frontend.rstrip("/")
        self.batch_code = batch_code
        self.timeout = timeout
        self.results: list[Result] = []
        self.batch_id: int | None = None
        self.inspection_count = 0
        # Bypass HTTP(S)_PROXY for loopback checks.
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def http(self, url: str) -> tuple[int, Any, str]:
        req = urllib.request.Request(
            url,
            headers={
                "Accept": "application/json,text/html;q=0.9,*/*;q=0.8",
                "User-Agent": "ZhiJian-I4-Smoke/0.1",
            },
            method="GET",
        )
        try:
            with self.opener.open(req, timeout=self.timeout) as resp:
                status = int(resp.status)
                content_type = resp.headers.get("Content-Type", "")
                raw = resp.read()
        except urllib.error.HTTPError as err:
            status = int(err.code)
            content_type = err.headers.get("Content-Type", "")
            raw = err.read()
        text = raw.decode("utf-8", errors="replace")
        if "json" in content_type.lower():
            try:
                return status, json.loads(text), content_type
            except json.JSONDecodeError:
                pass
        return status, text, content_type

    def check(self, name: str, fn: Callable[[], str | None]) -> None:
        try:
            detail = fn() or ""
        except Exception as exc:
            self.results.append(Result("FAIL", name, f"{type(exc).__name__}: {exc}"))
        else:
            self.results.append(Result("PASS", name, detail))

    @staticmethod
    def obj(status: int, body: Any, expected: int = 200) -> dict[str, Any]:
        if status != expected:
            raise AssertionError(f"HTTP {status}, expected {expected}")
        if not isinstance(body, dict):
            raise AssertionError("response is not a JSON object")
        return body

    def ai_health(self) -> str:
        status, body, _ = self.http(self.ai + "/health")
        data = self.obj(status, body)
        if data.get("status") != "ok" or data.get("service") != "zhijian-ai":
            raise AssertionError(f"unexpected payload: {data}")
        return f"model_ready={data.get('model_ready')}, model_version={data.get('model_version')}"

    def ai_ready(self) -> str:
        status, body, _ = self.http(self.ai + "/ready")
        data = self.obj(status, body)
        if data.get("model_ready") is not True:
            raise AssertionError(f"model not ready: {data}")
        version = data.get("model_version")
        if not isinstance(version, str) or not version:
            raise AssertionError("model_version missing")
        return version

    def backend_health(self) -> str:
        status, body, _ = self.http(self.backend + "/health")
        data = self.obj(status, body)
        if data != {"status": "ok"}:
            raise AssertionError(f"unexpected payload: {data}")
        return self.backend

    def backend_inspection_ready(self) -> str:
        status, body, _ = self.http(self.backend + "/inspection-service/ready")
        data = self.obj(status, body)
        for key in ("ready", "database_ready", "model_ready"):
            if data.get(key) is not True:
                raise AssertionError(f"{key} is not true: {data}")
        return "database_ready=true, model_ready=true"

    def batches(self) -> str:
        status, body, _ = self.http(self.backend + "/batches")
        if status != 200:
            raise AssertionError(f"HTTP {status}")
        if not isinstance(body, list):
            raise AssertionError("/batches is not a JSON array")
        target = next(
            (x for x in body if isinstance(x, dict) and x.get("batch_code") == self.batch_code),
            None,
        )
        if target is None:
            raise AssertionError(f"batch not found: {self.batch_code}")
        batch_id = target.get("batch_id")
        if not isinstance(batch_id, int) or batch_id < 1:
            raise AssertionError(f"invalid batch_id: {batch_id!r}")
        self.batch_id = batch_id
        return f"{self.batch_code} -> batch_id={batch_id}; DB path reachable through backend"

    def batch_detail(self) -> str:
        if self.batch_id is None:
            raise AssertionError("batch_id unavailable")
        status, body, _ = self.http(f"{self.backend}/batches/{self.batch_id}")
        data = self.obj(status, body)
        if data.get("batch_code") != self.batch_code:
            raise AssertionError("batch_code mismatch")
        events = data.get("events")
        if not isinstance(events, list):
            raise AssertionError("events is not an array")
        return f"events={len(events)}"

    def inspections(self) -> str:
        if self.batch_id is None:
            raise AssertionError("batch_id unavailable")
        status, body, _ = self.http(f"{self.backend}/batches/{self.batch_id}/inspections")
        if status != 200:
            raise AssertionError(f"HTTP {status}")
        if not isinstance(body, list):
            raise AssertionError("inspection history is not an array")
        self.inspection_count = len(body)
        return f"records={self.inspection_count}"

    def report(self) -> str:
        if self.batch_id is None:
            raise AssertionError("batch_id unavailable")
        status, body, _ = self.http(f"{self.backend}/batches/{self.batch_id}/report")
        data = self.obj(status, body)
        if data.get("schema_version") != "zhijian.batch.report.v1":
            raise AssertionError(f"unexpected schema_version: {data.get('schema_version')!r}")
        if data.get("scope") != "INTERNAL_DEMO_AGGREGATION_ONLY":
            raise AssertionError(f"unexpected scope: {data.get('scope')!r}")
        batch = data.get("batch")
        if not isinstance(batch, dict) or batch.get("batch_code") != self.batch_code:
            raise AssertionError("report batch mismatch")
        availability = data.get("data_availability")
        if not isinstance(availability, dict):
            raise AssertionError("data_availability missing")
        return "availability=" + json.dumps(availability, ensure_ascii=False, separators=(",", ":"))

    @staticmethod
    def forbidden_hits(value: Any, forbidden: set[str], path: str = "$") -> list[str]:
        hits: list[str] = []
        if isinstance(value, dict):
            for key, child in value.items():
                child_path = f"{path}.{key}"
                if key in forbidden:
                    hits.append(child_path)
                hits.extend(Smoke.forbidden_hits(child, forbidden, child_path))
        elif isinstance(value, list):
            for idx, child in enumerate(value):
                hits.extend(Smoke.forbidden_hits(child, forbidden, f"{path}[{idx}]"))
        return hits

    def trace(self) -> str:
        status, body, _ = self.http(f"{self.backend}/trace/{self.batch_code}")
        data = self.obj(status, body)
        if data.get("batch_code") != self.batch_code:
            raise AssertionError("trace batch_code mismatch")

        summary = data.get("public_summary")
        if not isinstance(summary, dict):
            raise AssertionError("public_summary missing")
        expected_stages = {"inspection", "processing", "coldchain", "logistics"}
        if set(summary) != expected_stages:
            raise AssertionError(f"summary stages mismatch: {sorted(summary)}")

        for stage in sorted(expected_stages):
            item = summary.get(stage)
            if not isinstance(item, dict):
                raise AssertionError(f"{stage} summary is not an object")
            count = item.get("record_count")
            state = item.get("state")
            latest = item.get("latest_event_time")
            if not isinstance(count, int) or count < 0:
                raise AssertionError(f"{stage}.record_count invalid: {count!r}")
            expected_state = "recorded" if count > 0 else "no_public_record"
            if state != expected_state:
                raise AssertionError(f"{stage}.state={state!r}, expected {expected_state!r}")
            if count == 0 and latest is not None:
                raise AssertionError(f"{stage}.latest_event_time should be null when count=0")

        events = data.get("events")
        if not isinstance(events, list):
            raise AssertionError("events is not an array")
        event_keys = {"event_type", "event_time", "summary", "source"}
        for idx, event in enumerate(events):
            if not isinstance(event, dict) or set(event) != event_keys:
                raise AssertionError(f"public event {idx} fields differ from contract")

        forbidden = {
            "batch_id", "supplier", "model_version", "weights_sha256",
            "confidence_threshold", "iou_threshold", "detections",
            "candidate_reviews", "artifact_urls", "evidence_path",
            "reviewer", "remark", "final_grade",
        }
        hits = self.forbidden_hits(data, forbidden)
        if hits:
            raise AssertionError("private/internal keys leaked: " + ", ".join(hits[:12]))

        counts = {k: summary[k]["record_count"] for k in sorted(expected_stages)}
        return f"events={len(events)}, public_counts={counts}, privacy_guard=ok"

    def frontend_root(self) -> str:
        status, body, content_type = self.http(self.frontend + "/")
        if status != 200:
            raise AssertionError(f"HTTP {status}")
        if not isinstance(body, str):
            raise AssertionError("frontend returned JSON instead of HTML")
        lower = body.lower()
        if "<html" not in lower or 'id="app"' not in lower:
            raise AssertionError("response does not look like Vue index.html")
        return content_type or "text/html"

    def run(self) -> int:
        print("=" * 64)
        print("ZhiJian Full Demo Smoke Test (I4, read-only)")
        print("=" * 64)
        print(f"Backend : {self.backend}")
        print(f"AI      : {self.ai}")
        print(f"Frontend: {self.frontend}")
        print(f"Batch   : {self.batch_code}")
        print()

        checks = [
            ("AI service health", self.ai_health),
            ("AI model ready", self.ai_ready),
            ("Backend health", self.backend_health),
            ("Backend inspection readiness", self.backend_inspection_ready),
            ("Batch list + database path", self.batches),
            ("Batch detail", self.batch_detail),
            ("Inspection history endpoint", self.inspections),
            ("Internal batch report", self.report),
            ("Public trace + privacy guard", self.trace),
            ("Frontend reachable", self.frontend_root),
        ]
        for name, fn in checks:
            self.check(name, fn)

        inspection_check_failed = any(
            r.level == "FAIL" and r.name == "Inspection history endpoint" for r in self.results
        )
        if not inspection_check_failed and self.inspection_count == 0:
            self.results.append(Result(
                "WARN",
                "Inspection data",
                "endpoint is healthy but this database has no saved A3 inspection records yet",
            ))

        for r in self.results:
            suffix = f" - {r.detail}" if r.detail else ""
            print(f"[{r.level}] {r.name}{suffix}")

        passed = sum(r.level == "PASS" for r in self.results)
        failed = sum(r.level == "FAIL" for r in self.results)
        warned = sum(r.level == "WARN" for r in self.results)
        print()
        print("-" * 64)
        print(f"PASS {passed:>3}   FAIL {failed:>3}   WARN {warned:>3}")
        if failed == 0:
            print("FULL DEMO READY (for the checks above)")
            print("Note: engineering smoke test only; not food-safety/model-accuracy certification.")
            return 0
        print("FULL DEMO NOT READY")
        print("Fix FAIL items above, then rerun the same command.")
        return 1


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Read-only ZhiJian full Demo smoke test")
    p.add_argument("--backend", default="http://127.0.0.1:8080")
    p.add_argument("--ai", default="http://127.0.0.1:8001")
    p.add_argument("--frontend", default="http://127.0.0.1:5173")
    p.add_argument("--batch-code", default="APPLE-2026-001")
    p.add_argument("--timeout", type=float, default=5.0)
    return p.parse_args()


if __name__ == "__main__":
    args = parse_args()
    sys.exit(Smoke(args.backend, args.ai, args.frontend, args.batch_code, args.timeout).run())
