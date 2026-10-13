"""Read-only acceptance check for C's business integration."""
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request


def get(url: str, timeout: float):
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}: {url}")
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only C integration smoke test")
    parser.add_argument("--backend", default="http://127.0.0.1:8080")
    parser.add_argument("--frontend", default="http://127.0.0.1:5173")
    parser.add_argument("--timeout", type=float, default=5)
    args = parser.parse_args()
    try:
        ready = get(f"{args.backend}/business-service/ready", args.timeout)
        if not ready.get("ready"):
            raise RuntimeError(f"business integration is not ready: {ready}")
        batches = get(f"{args.backend}/batches", args.timeout)
        if not isinstance(batches, list) or not batches:
            raise RuntimeError("no database batch is available")
        batch_id = batches[0].get("batch_id")
        if not isinstance(batch_id, int) or batch_id < 1:
            raise RuntimeError("batch list has no valid internal ID")
        processing = get(f"{args.backend}/batches/{batch_id}/processing-records", args.timeout)
        coldchain = get(f"{args.backend}/batches/{batch_id}/coldchain", args.timeout)
        report = get(f"{args.backend}/batches/{batch_id}/report", args.timeout)
        if not isinstance(processing.get("records"), list):
            raise RuntimeError("processing history has an invalid schema")
        if not isinstance(coldchain.get("readings"), list) or not isinstance(coldchain.get("alerts"), list):
            raise RuntimeError("coldchain history has an invalid schema")
        if report.get("batch", {}).get("batch_id") != batch_id:
            raise RuntimeError("report batch does not match the selected batch")
        with urllib.request.urlopen(args.frontend, timeout=args.timeout) as response:
            html = response.read().decode("utf-8")
            if response.status != 200 or '<div id="app"></div>' not in html:
                raise RuntimeError("frontend entry page is unavailable")
        print(json.dumps({
            "ready": True,
            "batch_id": batch_id,
            "processing_records": len(processing["records"]),
            "readings": len(coldchain["readings"]),
            "alerts": len(coldchain["alerts"]),
            "report_warnings": len(report.get("warnings", [])),
        }, ensure_ascii=False, indent=2))
        print("C INTEGRATION READY (read-only checks)")
        return 0
    except (OSError, ValueError, RuntimeError, urllib.error.HTTPError) as error:
        print(f"C INTEGRATION NOT READY: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
