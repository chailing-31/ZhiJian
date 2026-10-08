from datetime import datetime, timedelta, timezone
from typing import Annotated, Literal

from pydantic import BaseModel, BeforeValidator, ConfigDict, Field, field_validator, model_validator


SHANGHAI = timezone(timedelta(hours=8))
BatchId = Annotated[int, Field(strict=True, gt=0, le=9223372036854775807)]
Source = Literal["simulation", "sensor"]


def reject_boolean_number(value):
    # Keep numeric CSV strings valid while rejecting JSON true/false as 1/0.
    if isinstance(value, bool):
        raise ValueError("数值字段不能使用布尔值")
    return value


InputNumber = Annotated[float, BeforeValidator(reject_boolean_number)]


class Model(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class Reading(Model):
    timestamp: datetime
    temperature: InputNumber
    humidity: InputNumber = Field(ge=0, le=100)
    source: Source
    door_open: bool | None = Field(default=None, strict=True)
    equipment_current: InputNumber | None = Field(default=None, ge=0)

    @field_validator("timestamp", mode="before")
    @classmethod
    def reject_epoch(cls, value):
        if not isinstance(value, (str, datetime)):
            raise ValueError("timestamp 必须是带时区的 ISO 8601 时间")
        if isinstance(value, str):
            try:
                datetime.fromisoformat(value)
            except ValueError as error:
                raise ValueError("timestamp 必须是带时区的 ISO 8601 时间") from error
        return value

    @field_validator("timestamp")
    @classmethod
    def normalize_time(cls, value: datetime) -> datetime:
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("timestamp 必须包含时区，例如 +08:00")
        try:
            return value.astimezone(SHANGHAI)
        except OverflowError as error:
            raise ValueError("timestamp 转换为 +08:00 后超出支持的时间范围") from error


class DemoConfig(Model):
    temperature_upper: InputNumber = 8
    duration_minutes: InputNumber = Field(default=5, gt=0, le=1440)
    max_gap_minutes: InputNumber = Field(default=2, gt=0, le=1440)


class ColdchainRequest(Model):
    batch_id: BatchId
    readings: list[Reading] = Field(min_length=1, max_length=10000)
    config: DemoConfig = Field(default_factory=DemoConfig)

    @model_validator(mode="after")
    def validate_sequence(self):
        if any(a.timestamp >= b.timestamp for a, b in zip(self.readings, self.readings[1:])):
            raise ValueError("readings 必须按时间严格递增，不允许重复时间")
        if len({reading.source for reading in self.readings}) != 1:
            raise ValueError("一次分析不能混用 simulation 与 sensor 数据")
        return self


class Episode(Model):
    started_at: datetime
    triggered_at: datetime
    trigger_value: float
    last_observed_at: datetime
    recovered_at: datetime | None
    end_reason: Literal["ongoing", "recovered", "data_gap"]
    observed_minutes: float
    peak_temperature: float


class ColdchainResponse(Model):
    batch_id: BatchId
    source: Source
    alert: bool
    level: Literal["HIGH", "NONE"]
    status: Literal["active", "pending", "insufficient_data", "normal"]
    reason: str
    episodes: list[Episode]
    data_gap_count: int
    rule_version: str
    demo_only: bool = True
    config: DemoConfig


class ProcessRequest(Model):
    batch_id: BatchId
    grade: Literal["A", "B", "C", "REJECT"]
    temperature: InputNumber  # Environment temperature, Celsius.
    humidity: InputNumber = Field(ge=0, le=100)
    material_temperature: InputNumber | None = None


class Advice(Model):
    pre_cooling_time: str | None = None
    washing_pressure: str | None = None
    action: str


class ProcessResponse(Model):
    batch_id: BatchId
    advice_type: Literal["rule"] = "rule"
    advice: Advice
    reason: str
    rule_version: str
    requires_confirmation: bool = True
    demo_only: bool = True
