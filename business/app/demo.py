"""Independent rule demos; optional HTTP verification, never writes business data."""

import argparse
import json
import math
from pathlib import Path
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

from .analyze_csv import load_csv
from .models import ColdchainResponse, DemoConfig, ProcessRequest, ProcessResponse
from .rules import check_coldchain, process_advice


def evaluate(path, request, response_type, calculate, base_url=None):
    expected = calculate(request)
    if base_url is None:
        return expected
    url = urlsplit(base_url)
    if url.scheme not in {"http", "https"} or not url.netloc or url.query or url.fragment or url.username:
        raise ValueError("base-url 须为不含账号、查询参数或片段的 HTTP(S) 服务地址")
    payload = json.dumps(request.model_dump(mode="json"), allow_nan=False).encode("utf-8")
    query = Request(base_url.rstrip("/") + path, data=payload,
                    headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urlopen(query, timeout=15) as response:
            actual = response_type.model_validate_json(response.read())
    except HTTPError as error:
        raise ValueError(f"B 服务返回 HTTP {error.code}，未确认本次计算成功；检查接口与服务日志") from error
    except (URLError, TimeoutError) as error:
        raise ValueError("无法连接 B 服务；请检查 --base-url 和服务是否启动") from error
    if actual != expected:
        raise ValueError("服务结果与本地规则不一致，请核对服务版本和配置；未将结果标为通过")
    return actual


def replay(sequence, *, base_url=None, interval=0):
    """Feed complete prefixes, without storing server-side state or changing timestamps."""
    if not math.isfinite(interval) or not 0 <= interval <= 10:
        raise ValueError("播放间隔须为 0—10 秒的有限数值")
    for index in range(len(sequence.readings)):
        prefix = sequence.model_copy(update={"readings": sequence.readings[:index + 1]})
        result = evaluate("/coldchain/check", prefix, ColdchainResponse, check_coldchain, base_url)
        yield index + 1, result
        if interval and index + 1 < len(sequence.readings):
            time.sleep(interval)


def main():
    parser = argparse.ArgumentParser(description="B 独立演示：默认离线，可选直连 Python 接口核验；不需要系统集成")
    commands = parser.add_subparsers(dest="command", required=True)
    processing = commands.add_parser("processing", help="根据请求 JSON 输出加工建议")
    processing.add_argument("request", type=Path)
    coldchain = commands.add_parser("coldchain", help="按 CSV 采样时间逐点演示连续检测")
    coldchain.add_argument("csv", type=Path)
    coldchain.add_argument("--batch-id", type=int, default=1, help="本次离线演示用正整数标识，不查询数据库")
    coldchain.add_argument("--config", type=Path)
    coldchain.add_argument("--interval", type=float, default=0, help="墙上播放间隔秒数，不改变采样时间")
    for command in (processing, coldchain):
        command.add_argument("--base-url", help="可选 Python 服务地址，例如 http://127.0.0.1:8001")
        command.add_argument("--output", type=Path, help="可选输出 JSON 文件；存在时拒绝覆盖")
    args = parser.parse_args()
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        if args.output and args.output.exists():
            raise ValueError("输出文件已存在，请选择新文件名")
        if args.command == "processing":
            request = ProcessRequest.model_validate_json(args.request.read_text(encoding="utf-8-sig"))
            result = evaluate("/business/process-advice", request, ProcessResponse, process_advice, args.base_url)
            report = result.model_dump(mode="json")
        else:
            config = DemoConfig.model_validate_json(args.config.read_text(encoding="utf-8-sig")) if args.config else None
            # Validate the entire file before displaying successful steps or making HTTP calls.
            sequence = load_csv(args.csv, args.batch_id, config)
            steps = []
            for index, result in replay(sequence, base_url=args.base_url, interval=args.interval):
                step = {"index": index, "timestamp": sequence.readings[index - 1].timestamp.isoformat(),
                        "temperature": sequence.readings[index - 1].temperature,
                        "status": result.status, "alert": result.alert,
                        "episode_count": len(result.episodes), "data_gap_count": result.data_gap_count}
                steps.append(step)
                print(f"{index}/{len(sequence.readings)} {step['timestamp']} -> {result.status}, alert={result.alert}", file=sys.stderr)
            report = {"mode": "http_verified" if args.base_url else "offline", "steps": steps,
                      "final_result": result.model_dump(mode="json")}
        output = json.dumps(report, ensure_ascii=False, allow_nan=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            with args.output.open("x", encoding="utf-8") as stream:
                stream.write(output)
        print(output, end="")
    except (OSError, ValueError) as error:
        parser.exit(2, f"演示失败：{error}\n")
    except KeyboardInterrupt:
        parser.exit(130, "已停止演示；B 服务未保存任何业务记录。\n")


if __name__ == "__main__":
    main()
