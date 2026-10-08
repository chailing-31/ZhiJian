from datetime import datetime
from typing import Literal

from pydantic import Field

from .models import BatchId, ColdchainResponse, Model, Source


class FeatureConfig(Model):
    profile: Literal["environment", "device"] = "device"
    window_minutes: float = Field(default=5, gt=0, le=60)
    max_gap_minutes: float = Field(default=2, gt=0, le=60)
    min_points: int = Field(default=3, ge=2, le=100)


class FeatureEvidence(Model):
    feature: str
    value: float
    reference_low: float
    reference_high: float
    interpretation: Literal["above_reference", "below_reference"]
    reference_type: Literal["training_percentiles", "normal_neighbors"] = "training_percentiles"


class AnomalyPoint(Model):
    timestamp: datetime
    status: Literal["scored", "insufficient_history", "missing_device_fields"]
    score: float | None = None
    raw_score: float | None = None
    score_components: dict[str, float] = Field(default_factory=dict)
    anomaly: bool | None = None
    evidence: list[FeatureEvidence] = Field(default_factory=list)


class AnomalyResult(Model):
    batch_id: BatchId
    algorithm: Literal["isolation_forest", "knn_distance", "isolation_forest_knn"] = "isolation_forest"
    model_id: str
    model_strategy: str = "joint"
    feature_version: str
    feature_config: FeatureConfig
    training_source: Source
    input_source: Source
    source_matches_training: bool
    demo_only: bool = True
    threshold: float
    threshold_method: str
    confirmation_points: int = 1
    feature_names: list[str]
    scored_count: int
    anomaly_count: int
    points: list[AnomalyPoint]
    explanation: str = "证据仅表示特征偏离训练参考分布，不是模型因果归因或设备故障诊断。"


class CombinedResult(Model):
    rule_result: ColdchainResponse
    model_result: AnomalyResult
