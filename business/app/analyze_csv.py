"""Offline CSV adapter. It never writes business data or calls a server."""

import argparse
import csv
import json
import sys
from pathlib import Path

from .models import ColdchainRequest, DemoConfig
from .rules import check_coldchain


REQUIRED = {"timestamp", "temperature", "humidity", "source"}
OPTIONAL = {"door_open", "equipment_current"}


def load_csv(path: Path, batch_id: int, config: DemoConfig | None = None) -> ColdchainRequest:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        headers = reader.fieldnames or []
        if (not REQUIRED.issubset(headers) or set(headers) - REQUIRED - OPTIONAL
                or len(set(headers)) != len(headers)):
            raise ValueError("CSV 表头须包含 timestamp,temperature,humidity,source；可选 door_open,equipment_current")
        readings = []
        for line, row in enumerate(reader, start=2):
            if None in row or any(value is None for value in row.values()):
                raise ValueError(f"CSV 第 {line} 行列数不匹配")
            row = {key: value.strip() for key, value in row.items()}
            for field in OPTIONAL:
                if field in row and row[field] == "":
                    row[field] = None
            if row.get("door_open") is not None:
                boolean = row["door_open"].lower()
                if boolean not in {"true", "false", "1", "0"}:
                    raise ValueError(f"CSV 第 {line} 行 door_open 须为 true/false/1/0")
                row["door_open"] = boolean in {"true", "1"}
            readings.append(row)
            if len(readings) > 10000:
                raise ValueError("单次分析最多 10000 条读数")
    return ColdchainRequest(batch_id=batch_id, readings=readings, config=config or DemoConfig())


def main():
    parser = argparse.ArgumentParser(description="离线检测冷链 CSV，不写入数据库")
    parser.add_argument("csv", type=Path)
    parser.add_argument("--batch-id", type=int, required=True)
    parser.add_argument("--config", type=Path, help="演示阈值 JSON 文件")
    args = parser.parse_args()
    try:
        config = DemoConfig.model_validate_json(args.config.read_text(encoding="utf-8")) if args.config else None
        result = check_coldchain(load_csv(args.csv, args.batch_id, config))
    except (OSError, ValueError) as error:
        parser.exit(2, f"分析失败：{error}\n")
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(result.model_dump(mode="json"), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
