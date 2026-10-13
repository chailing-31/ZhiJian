"""Versioned demo recipes, separate from cold-chain calculations."""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator

from .models import Advice, InputNumber, Model, ProcessRequest, ProcessResponse


class InputRange(Model):
    minimum: InputNumber
    maximum: InputNumber

    @model_validator(mode="after")
    def check_order(self):
        if self.minimum > self.maximum:
            raise ValueError("规则范围下限不能大于上限")
        return self


class GradeRule(Model):
    rule_id: str = Field(min_length=1)
    status: Literal["suggested", "manual_review", "blocked"]
    pre_cooling_time: str | None = Field(default=None, pattern=r"^[1-9][0-9]*h$")
    washing_pressure: Literal["normal", "low"] | None = None
    action: str = Field(min_length=1)

    @model_validator(mode="after")
    def check_parameters(self):
        if self.status == "suggested":
            if self.pre_cooling_time is None or self.washing_pressure is None:
                raise ValueError("建议状态必须提供完整加工参数")
        elif self.pre_cooling_time is not None or self.washing_pressure is not None:
            raise ValueError("复核或暂停状态不能包含可采用的加工参数")
        return self


class ProcessingConfig(Model):
    version: str = Field(min_length=1)
    basis: str = Field(min_length=1)
    temperature: InputRange
    humidity: InputRange
    material_temperature: InputRange
    grades: dict[Literal["A", "B", "C", "REJECT"], GradeRule]

    @model_validator(mode="after")
    def check_grades(self):
        if set(self.grades) != {"A", "B", "C", "REJECT"}:
            raise ValueError("加工规则必须覆盖四个等级")
        if self.grades["REJECT"].status != "blocked":
            raise ValueError("拒收等级必须为暂停状态")
        if len({rule.rule_id for rule in self.grades.values()}) != 4:
            raise ValueError("规则标识不能重复")
        if not 0 <= self.humidity.minimum <= self.humidity.maximum <= 100:
            raise ValueError("规则湿度范围必须在0—100内")
        return self


@lru_cache(maxsize=1)
def load_processing_config() -> ProcessingConfig:
    path = Path(__file__).resolve().parents[1] / "config" / "processing-rules.json"
    return ProcessingConfig.model_validate_json(path.read_text(encoding="utf-8"))


def process_advice(request: ProcessRequest) -> ProcessResponse:
    config = load_processing_config()
    rule = config.grades[request.grade]
    status = rule.status
    rule_id = rule.rule_id
    advice = Advice(pre_cooling_time=rule.pre_cooling_time,
                    washing_pressure=rule.washing_pressure, action=rule.action)
    observations = []
    outside = []
    for field, label, unit in (
        ("temperature", "环境温度", "℃"),
        ("humidity", "环境湿度", "%RH"),
        ("material_temperature", "原料温度", "℃"),
    ):
        value = getattr(request, field)
        bounds = getattr(config, field)
        if value is None:
            observations.append(f"{label}未提供，未使用环境温度替代")
            continue
        observations.append(f"{label}{value:g}{unit}")
        if not bounds.minimum <= value <= bounds.maximum:
            outside.append(
                f"{label}{value:g}{unit}超出演示适用范围"
                f"[{bounds.minimum:g},{bounds.maximum:g}]{unit}"
            )

    if rule.status == "blocked":
        decision = "拒收等级暂停流转，不提供加工参数；未执行设备或系统流转控制"
    elif rule.status == "manual_review":
        decision = "该等级需负责人复核加工适用性，暂不提供加工参数"
    elif outside:
        status = "manual_review"
        rule_id = f"{rule.rule_id}-OUT-OF-SCOPE"
        advice = Advice(action="输入超出演示规则覆盖范围，请人工复核并记录实际采用参数")
        decision = "未匹配适用的参数建议"
    else:
        decision = (
            f"命中演示规则，建议预冷{advice.pre_cooling_time}、"
            f"清洗压力档位{advice.washing_pressure}；温湿度仅用于适用性判断，不用于计算最优时长"
        )
    details = "；".join(observations + outside)
    return ProcessResponse(
        batch_id=request.batch_id, status=status, rule_id=rule_id,
        rule_basis=config.basis, advice=advice,
        reason=f"人工确认等级{request.grade}；{details}；{decision}。仅供演示，实际参数须人工确认。",
        rule_version=config.version,
    )
