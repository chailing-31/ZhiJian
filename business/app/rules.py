"""Reconstructed from Fast's Python 3.12 bytecode; contract updated for ZhiJian."""

from .models import (
    Advice, ColdchainRequest, ColdchainResponse, Episode, ProcessRequest,
    ProcessResponse, Reading,
)


COLDCHAIN_VERSION = "demo-coldchain-v2"
PROCESS_VERSION = "demo-process-v2"


def check_coldchain(request: ColdchainRequest) -> ColdchainResponse:
    config = request.config
    episodes: list[Episode] = []
    run: list[Reading] = []
    gap_count = 0

    def close_run(reason, recovered_at=None):
        if not run:
            return
        duration = (run[-1].timestamp - run[0].timestamp).total_seconds() / 60
        if duration < config.duration_minutes:
            return
        triggered = next(
            reading for reading in run
            if (reading.timestamp - run[0].timestamp).total_seconds() / 60
            >= config.duration_minutes
        )
        episodes.append(Episode(
            started_at=run[0].timestamp,
            triggered_at=triggered.timestamp,
            trigger_value=triggered.temperature,
            last_observed_at=run[-1].timestamp,
            recovered_at=recovered_at,
            end_reason=reason,
            observed_minutes=duration,
            peak_temperature=max(reading.temperature for reading in run),
        ))

    previous = None
    for reading in request.readings:
        if previous and (
            (reading.timestamp - previous.timestamp).total_seconds() / 60
            > config.max_gap_minutes
        ):
            gap_count += 1
            close_run("data_gap")
            run = []
        if reading.temperature > config.temperature_upper:
            run.append(reading)
        else:
            close_run("recovered", reading.timestamp)
            run = []
        previous = reading
    close_run("ongoing")

    active = bool(episodes and episodes[-1].end_reason == "ongoing")
    if active:
        status = "active"
        reason = (
            f"观测到温度高于 {config.temperature_upper:g}℃ 持续至少 "
            f"{config.duration_minutes:g} 分钟（演示规则）"
        )
    elif run and len(run) < 2:
        status = "insufficient_data"
        reason = "当前读数超温，但连续历史数据不足，不能确认持续超温"
    elif run:
        status = "pending"
        reason = "当前读数超温，观测持续时间尚未达到演示阈值"
    else:
        status = "normal"
        reason = "当前读数未超过演示温度阈值；历史异常请查看 episodes"

    return ColdchainResponse(
        batch_id=request.batch_id, source=request.readings[0].source,
        alert=active, level="HIGH" if active else "NONE", status=status,
        reason=reason, episodes=episodes, data_gap_count=gap_count,
        rule_version=COLDCHAIN_VERSION, config=config,
    )


def process_advice(request: ProcessRequest) -> ProcessResponse:
    actions = {
        "A": "按已批准的作业规程执行，并记录采用参数",
        "B": "人工复核外观缺陷与分选结果，再按已批准的作业规程执行",
        "C": "交由负责人复核用途与加工适用性，确认后再执行",
        "REJECT": "暂停本批次加工流转，交由负责人复核处置",
    }
    return ProcessResponse(
        batch_id=request.batch_id,
        advice=Advice(action=actions[request.grade]),
        reason=(
            f"根据人工确认等级 {request.grade} 给出演示流程提示；温湿度仅作为输入记录，"
            "尚未建立经核定的工艺参数映射，预冷时长和清洗水压不提供数值建议"
        ),
        rule_version=PROCESS_VERSION,
    )
