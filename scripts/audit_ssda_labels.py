"""Read-only SSDA YOLO label audit.

Example:
  python scripts\audit_ssda_labels.py --root D:\path\to\ssda --json-out audit.json
"""

from __future__ import annotations
import argparse
import json
from collections import Counter
from pathlib import Path

EXPECTED = {
    "class0_scratch_class1_pest": {
        "train": {0: 500, 1: 716},
        "val": {0: 124, 1: 172},
    },
    "class0_pest_class1_scratch": {
        "train": {0: 716, 1: 500},
        "val": {0: 172, 1: 124},
    },
}

def scan_split(root: Path, split: str):
    label_dir = root / "labels" / split
    if not label_dir.is_dir():
        raise FileNotFoundError(f"missing label directory: {label_dir}")
    counts = Counter()
    files = sorted(label_dir.glob("*.txt"))
    empty = 0
    malformed = []
    for path in files:
        lines = [x.strip() for x in path.read_text(encoding="utf-8-sig", errors="replace").splitlines() if x.strip()]
        if not lines:
            empty += 1
        for line_no, line in enumerate(lines, 1):
            parts = line.split()
            try:
                if len(parts) != 5:
                    raise ValueError(f"expected 5 fields, got {len(parts)}")
                raw_id = float(parts[0])
                if raw_id != int(raw_id):
                    raise ValueError("class ID is not integer-valued")
                class_id = int(raw_id)
                coords = [float(x) for x in parts[1:]]
                if not all(0.0 <= x <= 1.0 for x in coords):
                    raise ValueError("YOLO coordinate outside [0,1]")
                counts[class_id] += 1
            except ValueError as exc:
                malformed.append({"file": str(path), "line": line_no, "reason": str(exc), "text": line})
    return {
        "file_count": len(files),
        "empty_label_files": empty,
        "box_count": sum(counts.values()),
        "class_counts": dict(sorted(counts.items())),
        "malformed_count": len(malformed),
        "malformed_examples": malformed[:20],
    }

def infer(splits):
    train = {int(k): int(v) for k, v in splits["train"]["class_counts"].items()}
    val = {int(k): int(v) for k, v in splits["val"]["class_counts"].items()}
    if train == EXPECTED["class0_scratch_class1_pest"]["train"] and val == EXPECTED["class0_scratch_class1_pest"]["val"]:
        return {"status": "CONFIRMED_BY_EXACT_COUNT_MATCH",
                "mapping": {"0": {"en": "scratch", "zh": "表面擦伤"},
                            "1": {"en": "pest_damage", "zh": "虫害损伤"}}}
    if train == EXPECTED["class0_pest_class1_scratch"]["train"] and val == EXPECTED["class0_pest_class1_scratch"]["val"]:
        return {"status": "CONFIRMED_BY_EXACT_COUNT_MATCH",
                "mapping": {"0": {"en": "pest_damage", "zh": "虫害损伤"},
                            "1": {"en": "scratch", "zh": "表面擦伤"}}}
    return {"status": "NOT_CONFIRMED", "mapping": None}

def main():
    p = argparse.ArgumentParser()
    p.add_argument("--root", action="append", required=True,
                   help="Dataset root containing labels/train and labels/val. May be repeated.")
    p.add_argument("--json-out", default="")
    args = p.parse_args()
    results = []
    for raw in args.root:
        root = Path(raw)
        result = {"dataset_root": str(root), "splits": {
            "train": scan_split(root, "train"),
            "val": scan_split(root, "val"),
        }}
        result["class_mapping_inference"] = infer(result["splits"])
        results.append(result)
        print("=" * 72)
        print(root)
        for split in ("train", "val"):
            s = result["splits"][split]
            print(split, "files=", s["file_count"], "boxes=", s["box_count"],
                  "class_counts=", s["class_counts"], "malformed=", s["malformed_count"])
        print("mapping:", result["class_mapping_inference"])
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if all(r["class_mapping_inference"]["status"] == "CONFIRMED_BY_EXACT_COUNT_MATCH" for r in results) else 1

if __name__ == "__main__":
    raise SystemExit(main())
