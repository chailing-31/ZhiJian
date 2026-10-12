"""Verify frozen A15 bytes/configuration without importing torch or loading pickle.

From repository root:
  python ai-service/tools/verify_a15.py
  python ai-service/tools/verify_a15.py --require-tracked
  python ai-service/tools/verify_a15.py --check-ready
"""
from __future__ import annotations
import argparse
import hashlib
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

EXPECTED_SHA256 = "05c5329e01d4c3bea9a1ca48f336a215dbf985b914af63268c9e34fb01f96ed1"
EXPECTED_BYTES = 6252266
EXPECTED_VERSION = "ssda-y8n-a15-multidomain-20261006-05c5329e"
WEIGHT_REL = "ai-service/models/a15/best.pt"
MANIFEST_REL = "ai-service/models/a15/model-manifest.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def validate_config(cfg: dict) -> None:
    expected = {
        "task": "apple_surface_defect_detection",
        "model_version": EXPECTED_VERSION,
        "weights": "best.pt",
        "weights_sha256": EXPECTED_SHA256,
        "confidence_threshold": 0.25,
        "iou_threshold": 0.50,
        "image_size": 640,
        "evaluation_status": "not_evaluated",
        "evaluation_report": "../../../docs/AI_MODEL_A15.md",
    }
    for key, value in expected.items():
        if cfg.get(key) != value:
            raise ValueError(f"Manifest mismatch: {key}; expected {value!r}, got {cfg.get(key)!r}")
    classes = cfg.get("classes")
    if not isinstance(classes, list) or len(classes) != 2:
        raise ValueError("Manifest must declare exactly two classes.")
    if [(x.get("id"), x.get("name")) for x in classes] != [(0, "ssda_class_0"), (1, "ssda_class_1")]:
        raise ValueError("Manifest class mapping is not the frozen A15 mapping.")
    if any(not isinstance(x.get("label"), str) or not x["label"].strip() for x in classes):
        raise ValueError("Missing class label.")
    if not isinstance(cfg.get("training_data_source"), str) or not cfg["training_data_source"].strip():
        raise ValueError("Missing training-data source description.")


def verify_bundle(repo: Path, require_tracked: bool = False) -> dict:
    repo = repo.resolve()
    mf = repo / MANIFEST_REL
    weight = repo / WEIGHT_REL
    if not mf.is_file() or not weight.is_file():
        raise ValueError("A15 asset missing. Both best.pt and model-manifest.json must be in this checkout.")
    cfg = json.loads(mf.read_text(encoding="utf-8-sig"))
    validate_config(cfg)
    if weight.stat().st_size != EXPECTED_BYTES:
        raise ValueError("Weight size mismatch (possibly a Git LFS pointer, placeholder, or wrong model).")
    if sha256(weight) != EXPECTED_SHA256:
        raise ValueError("Weight SHA256 mismatch. Do not load this file as A15.")
    if not (mf.parent / cfg["evaluation_report"]).resolve().is_file():
        raise ValueError("The repository model card is missing.")
    if require_tracked:
        for rel in (WEIGHT_REL, MANIFEST_REL):
            r = subprocess.run(["git", "-C", str(repo), "show", ":" + rel], capture_output=True, check=False)
            if r.returncode:
                raise ValueError(f"Not present in Git index: {rel}. Stage the actual asset before committing.")
            if hashlib.sha256(r.stdout).hexdigest() != sha256(repo / rel):
                raise ValueError(f"Git index differs from local asset bytes: {rel}")
    return {"model_version": EXPECTED_VERSION, "weights_sha256": EXPECTED_SHA256, "bytes": EXPECTED_BYTES,
            "manifest": str(mf), "conf": 0.25, "nms_iou": 0.50, "imgsz": 640}


def check_ready() -> None:
    # Loopback only and bypass HTTP proxy settings.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open("http://127.0.0.1:8001/ready", timeout=10) as response:
        data = json.load(response)
    if data.get("model_ready") is not True or data.get("model_version") != EXPECTED_VERSION:
        raise ValueError("The running service is not ready with the expected A15 version: " + json.dumps(data))
    print("A15_SERVICE_READY")
    print("Readiness is not a real-image inference or accuracy test.")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--require-tracked", action="store_true")
    ap.add_argument("--check-ready", action="store_true")
    args = ap.parse_args()
    try:
        result = verify_bundle(args.repo, args.require_tracked)
        print(json.dumps(result, indent=2, ensure_ascii=True))
        print("A15_ASSET_VERIFIED")
        print("Byte/configuration check only; model was not deserialized.")
        if args.require_tracked:
            print("A15_ASSET_IN_GIT_INDEX")
        if args.check_ready:
            check_ready()
        return 0
    except (ValueError, OSError, KeyError, TypeError, AttributeError) as exc:
        print(f"A15_VERIFY_FAILED: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
