"""Export the running code's interface contract without starting any service."""

import argparse
import json
from pathlib import Path

from .main import app


def main():
    parser = argparse.ArgumentParser(description="导出 B 接口 OpenAPI，可导入接口测试工具")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
