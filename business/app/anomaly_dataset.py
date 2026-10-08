"""Explicit sequence-level splits and reproducible synthetic experiment fixtures."""

import argparse
import csv
import json
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np

from .anomaly import request_fingerprint
from .models import ColdchainRequest, Reading, SHANGHAI


@dataclass
class Sequence:
    sequence_id: str
    split: str
    scenario: str
    request: ColdchainRequest
    labels: list[int]


def load_dataset(path: Path) -> list[Sequence]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("format_version") != 1 or manifest.get("source") not in {"simulation", "sensor"}:
        raise ValueError("数据清单版本或来源无效")
    sequences, ids, batches, fingerprints = [], set(), {}, {}
    for entry in manifest["sequences"]:
        sequence_id, split = entry["id"], entry["split"]
        if sequence_id in ids or split not in {"train", "calibration", "validation", "test"}:
            raise ValueError("序列 ID 重复或数据划分无效")
        ids.add(sequence_id)
        batch_id = entry["batch_id"]
        if batch_id in batches and batches[batch_id] != split:
            raise ValueError("同一批次不能跨训练、校准或测试集")
        batches[batch_id] = split
        filename = (path.parent / entry["file"]).resolve()
        if not filename.is_relative_to(path.parent.resolve()):
            raise ValueError("CSV 路径须位于清单目录中")
        readings, labels = [], []
        with filename.open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            headers = reader.fieldnames or []
            required = {"timestamp", "temperature", "humidity", "source", "label"}
            if not required.issubset(headers) or len(headers) != len(set(headers)):
                raise ValueError("实验 CSV 缺少必需字段或存在重复表头")
            for row in reader:
                label = row.pop("label", None)
                if label not in {"0", "1"}:
                    raise ValueError("label 必须为0或1；未知标签不应混入已标注实验")
                if row.get("door_open") in {"true", "false", "1", "0"}:
                    row["door_open"] = row["door_open"] in {"true", "1"}
                for field in ["door_open", "equipment_current"]:
                    if row.get(field) == "":
                        row[field] = None
                readings.append(Reading.model_validate(row))
                labels.append(int(label))
                if len(readings) > 10000:
                    raise ValueError("单条实验序列最多10000个点")
        request = ColdchainRequest(batch_id=batch_id, readings=readings)
        if request.readings[0].source != manifest["source"]:
            raise ValueError("清单和 CSV 来源不一致")
        if split in {"train", "calibration"} and any(labels):
            raise ValueError("训练和校准序列必须经确认全部为正常工况")
        fingerprint = request_fingerprint(request)
        if fingerprint in fingerprints:
            raise ValueError("数据集含重复序列，不能用于独立评估")
        fingerprints[fingerprint] = split
        sequences.append(Sequence(sequence_id, split, entry["scenario"], request, labels))
    if not {"train", "calibration", "test"}.issubset({s.split for s in sequences}):
        raise ValueError("必须同时提供训练、正常校准和测试集")
    return sequences


def generate_dataset(directory: Path, seed: int = 20261003, with_validation: bool = False):
    directory.mkdir(parents=True, exist_ok=True)
    manifest_path = directory / "manifest.json"
    if manifest_path.exists():
        raise ValueError("输出目录已有数据清单，请使用新目录，避免覆盖实验输入")
    entries = []
    scenarios = ["normal", "normal_door", "overheat", "slow_drift", "oscillation", "humidity_drop", "current_spike", "data_gap"]
    jobs = [("train", "normal_door", i) for i in range(12)]
    jobs += [("calibration", "normal_door", i) for i in range(4)]
    if with_validation:
        jobs += [("validation", scenario, i) for scenario in scenarios for i in range(3)]
    jobs += [("test", scenario, i) for scenario in scenarios for i in range(3)]
    for ordinal, (split, scenario, repeat) in enumerate(jobs):
        rng = np.random.default_rng(seed + ordinal)
        start = datetime(2026, 1, 1, tzinfo=SHANGHAI) + timedelta(days=ordinal)
        sequence_id = f"{split}-{scenario}-{repeat:02d}"
        path = directory / f"{sequence_id}.csv"
        rows = []
        offset = rng.normal(0, 0.08)
        for minute in range(180):
            cycle = np.sin(minute / 8)
            door = scenario != "normal" and minute % 60 in {25, 26}
            temp = 4 + offset + 0.2 * cycle + rng.normal(0, 0.07) + (0.6 if door else 0)
            humidity = 85 + 0.6 * cycle + rng.normal(0, 0.15)
            current = 2.2 + 0.15 * cycle + rng.normal(0, 0.04)
            label = 0
            if 70 <= minute < 110:
                if scenario == "overheat":
                    temp += 7; label = 1
                elif scenario == "slow_drift":
                    temp += (minute - 69) * 0.075; label = 1
                elif scenario == "oscillation":
                    temp += 1.8 * (-1) ** minute; label = 1
                elif scenario == "humidity_drop":
                    humidity -= 18; label = 1
                elif scenario == "current_spike":
                    current += 3; label = 1
            if scenario == "data_gap" and 70 <= minute < 80:
                continue
            rows.append({
                "timestamp": (start + timedelta(minutes=minute)).isoformat(),
                "temperature": round(temp, 5), "humidity": round(humidity, 5),
                "source": "simulation", "door_open": str(door).lower(),
                "equipment_current": round(current, 5), "label": label,
            })
        with path.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
            writer.writeheader(); writer.writerows(rows)
        entries.append({"id": sequence_id, "batch_id": ordinal + 1, "split": split, "scenario": scenario, "file": path.name})
    manifest = {
        "format_version": 1, "source": "simulation", "seed": seed,
        "description": "人工构造的算法流程实验；4℃/85%为工程基线，不是红富士贮藏规范。label=1只表示注入区段，不表示实测故障。",
        "sequences": entries,
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return manifest_path


def main():
    parser = argparse.ArgumentParser(description="生成按序列划分的模拟实验数据")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20261003)
    parser.add_argument("--with-validation", action="store_true")
    args = parser.parse_args()
    try:
        manifest = generate_dataset(args.output, args.seed, args.with_validation)
    except (ValueError, OSError) as error:
        parser.exit(2, f"生成失败：{error}\n")
    print(manifest)


if __name__ == "__main__":
    main()
