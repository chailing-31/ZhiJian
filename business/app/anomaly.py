"""Causal window features and a separately trained/calibrated Isolation Forest."""

import hashlib
import json
from collections import deque
from dataclasses import dataclass
from pathlib import Path

import joblib
import numpy as np
import sklearn
from sklearn.ensemble import IsolationForest

from .anomaly_models import AnomalyPoint, AnomalyResult, FeatureConfig, FeatureEvidence
from .models import ColdchainRequest
from .novelty_distance import NormalDistance


FEATURE_VERSION = "coldchain-causal-v1"
DISTANCE_FEATURE_VERSION = "coldchain-causal-v2"
DISTANCE_STRATEGIES = {"distance", "hybrid_distance", "hybrid_persistent"}
STRATEGIES = ("joint", "grouped", "grouped_levels", "distance", "hybrid_distance", "hybrid_persistent")
ENV_FEATURES = [
    "temperature", "humidity", "temperature_rate_per_minute", "humidity_rate_per_minute",
    "temperature_std", "temperature_range", "humidity_std", "temperature_trend_per_minute",
]
DEVICE_FEATURES = ["door_open_fraction", "current_mean", "current_std", "current_rate_per_minute"]


@dataclass
class FeatureBatch:
    values: np.ndarray
    indices: list[int]
    statuses: list[str]
    names: list[str]


def extract_features(request: ColdchainRequest, config: FeatureConfig, version=FEATURE_VERSION) -> FeatureBatch:
    if version not in {FEATURE_VERSION, DISTANCE_FEATURE_VERSION}:
        raise ValueError("未知特征版本")
    names = ENV_FEATURES + (DEVICE_FEATURES if config.profile == "device" else [])
    if version == DISTANCE_FEATURE_VERSION and config.profile == "device":
        names = names + ["door_open_current", "equipment_current"]
    values, indices, statuses = [], [], []
    window = deque()
    previous = None
    for index, reading in enumerate(request.readings):
        if previous and (reading.timestamp - previous.timestamp).total_seconds() / 60 > config.max_gap_minutes:
            window.clear()
        previous = reading
        if config.profile == "device" and (reading.door_open is None or reading.equipment_current is None):
            window.clear()
            statuses.append("missing_device_fields")
            continue
        window.append(reading)
        # Keep one sample on/before the left boundary, including irregular sampling.
        while len(window) > 1 and (reading.timestamp - window[1].timestamp).total_seconds() / 60 >= config.window_minutes:
            window.popleft()
        span = (reading.timestamp - window[0].timestamp).total_seconds() / 60
        if len(window) < config.min_points or span < config.window_minutes:
            statuses.append("insufficient_history")
            continue
        temps = np.array([r.temperature for r in window], dtype=float)
        humidity = np.array([r.humidity for r in window], dtype=float)
        dt = (window[-1].timestamp - window[-2].timestamp).total_seconds() / 60
        row = [
            temps[-1], humidity[-1], (temps[-1] - temps[-2]) / dt,
            (humidity[-1] - humidity[-2]) / dt, temps.std(), np.ptp(temps),
            humidity.std(), (temps[-1] - temps[0]) / span,
        ]
        if config.profile == "device":
            current = np.array([r.equipment_current for r in window], dtype=float)
            row.extend([
                np.mean([r.door_open for r in window]), current.mean(), current.std(),
                (current[-1] - current[-2]) / dt,
            ])
            if version == DISTANCE_FEATURE_VERSION:
                row.extend([float(reading.door_open), reading.equipment_current])
        if not np.isfinite(row).all():
            raise ValueError("特征计算溢出，请检查读数幅值和采样时间间隔")
        values.append(row)
        indices.append(index)
        statuses.append("scored")
    return FeatureBatch(np.array(values, dtype=float).reshape(-1, len(names)), indices, statuses, names)


def request_fingerprint(request: ColdchainRequest) -> str:
    # Do not let a relabelled batch ID disguise identical training/calibration data.
    payload = [r.model_dump(mode="json") for r in request.readings]
    return hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()


class AnomalyDetector:
    def __init__(self, config: FeatureConfig, seed: int = 42, target_false_positive_rate: float = 0.02, strategy: str = "joint"):
        if not 0 < target_false_positive_rate < 0.5:
            raise ValueError("校准目标误报率须在0和0.5之间")
        if strategy not in STRATEGIES:
            raise ValueError("未知模型策略")
        self.config = config
        self.seed = seed
        self.strategy = strategy
        self.target_false_positive_rate = target_false_positive_rate
        self.metadata = None

    def fit(self, training: list[ColdchainRequest], calibration: list[ColdchainRequest]):
        if not training or not calibration:
            raise ValueError("必须分别提供正常训练集和正常校准集")
        fingerprints = [{request_fingerprint(r) for r in group} for group in [training, calibration]]
        if fingerprints[0] & fingerprints[1]:
            raise ValueError("训练集和校准集存在重复序列")
        if {r.batch_id for r in training} & {r.batch_id for r in calibration}:
            raise ValueError("训练和校准批次必须分离")
        sources = {r.readings[0].source for r in training + calibration}
        if len(sources) != 1:
            raise ValueError("训练和校准集不能混合模拟与传感器来源")
        self.feature_version = DISTANCE_FEATURE_VERSION if self.strategy in DISTANCE_STRATEGIES else FEATURE_VERSION
        batches = [[extract_features(r, self.config, self.feature_version) for r in group] for group in [training, calibration]]
        if any("missing_device_fields" in batch.statuses for group in batches for batch in group):
            raise ValueError("device 配置训练数据缺少门状态或电流；应补齐实际字段或显式选择 environment")
        train_x, calibration_x = [np.concatenate([b.values for b in group]) for group in batches]
        if len(train_x) < 64 or len(calibration_x) < 32:
            raise ValueError("至少需要64个正常训练窗口和32个独立正常校准窗口")
        self.names = batches[0][0].names
        self.estimator = IsolationForest(n_estimators=200, max_samples="auto", contamination="auto", random_state=self.seed, n_jobs=1)
        self.estimator.fit(train_x)
        if self.strategy in {"grouped", "grouped_levels"}:
            groups = {
                "joint": list(range(len(self.names))),
                "temperature": [0, 2, 4, 5, 7], "humidity": [1, 3, 6],
            }
            if self.config.profile == "device":
                groups["temperature"].append(8)  # Include door context.
                groups["equipment"] = [8, 9, 10, 11]
            if self.strategy == "grouped_levels":
                groups["temperature_level"] = [0]
                groups["humidity_level"] = [1]
                if self.config.profile == "device":
                    groups["current_level"] = [9]
            self.group_models = {}
            for name, columns in groups.items():
                estimator = self.estimator if name == "joint" else IsolationForest(
                    n_estimators=200, max_samples="auto", contamination="auto", random_state=self.seed, n_jobs=1,
                ).fit(train_x[:, columns])
                normal_scores = -estimator.score_samples(train_x[:, columns])
                center = float(np.median(normal_scores))
                scale = max(float(np.quantile(normal_scores, 0.95)) - center, 1e-6)
                self.group_models[name] = (columns, estimator, center, scale)
        if self.strategy in DISTANCE_STRATEGIES:
            self.distance = NormalDistance().fit(train_x)
            # Training-only normalization; the final threshold uses separate normal calibration.
            self.distance_normalizer = max(float(np.quantile(self.distance.training_scores, 0.98)), 1e-6)
            self.forest_normalizer = max(float(np.quantile(-self.estimator.score_samples(train_x), 0.98)), 1e-6)
            if self.strategy != "distance":
                self.level_columns = [0, 1, 12, 13] if self.config.profile == "device" else [0, 1]
                self.level_distance = NormalDistance().fit(train_x[:, self.level_columns])
                self.level_normalizer = max(float(np.quantile(self.level_distance.training_scores, 0.98)), 1e-6)
        calibration_scores = np.concatenate([
            self.confirm_scores(self.score(batch.values), batch, request)
            for batch, request in zip(batches[1], calibration) if len(batch.values)
        ])
        self.threshold = float(np.quantile(calibration_scores, 1 - self.target_false_positive_rate, method="higher"))
        self.reference_low = np.quantile(train_x, 0.01, axis=0)
        self.reference_high = np.quantile(train_x, 0.99, axis=0)
        self.reference_scale = np.maximum(np.quantile(train_x, 0.75, axis=0) - np.quantile(train_x, 0.25, axis=0), 1e-6)
        self.metadata = {
            "strategy": self.strategy,
            "score_method": ("normal_neighbor_distance" if self.strategy == "distance" else "max_normalized_forest_and_distance")
            if self.strategy in DISTANCE_STRATEGIES else ("negative_score_samples" if self.strategy == "joint" else "max_train_normalized_group_scores"),
            "feature_version": self.feature_version,
            "algorithm": "knn_distance" if self.strategy == "distance" else ("isolation_forest_knn" if self.strategy in DISTANCE_STRATEGIES else "isolation_forest"),
            "confirmation_points": 3 if self.strategy == "hybrid_persistent" else 1,
            "feature_config": self.config.model_dump(),
            "feature_names": self.names,
            "seed": self.seed,
            "training_source": sources.pop(),
            "training_windows": len(train_x), "calibration_windows": len(calibration_x),
            "training_batches": [r.batch_id for r in training],
            "calibration_batches": [r.batch_id for r in calibration],
            "training_fingerprints": sorted(fingerprints[0]),
            "calibration_fingerprints": sorted(fingerprints[1]),
            "target_false_positive_rate": self.target_false_positive_rate,
            "calibration_observed_false_positive_rate": float(np.mean(calibration_scores > self.threshold)),
            "threshold": self.threshold,
            "threshold_method": "independent_normal_calibration_quantile",
            "sklearn_version": sklearn.__version__,
            "demo_only": True,
        }
        if self.strategy in DISTANCE_STRATEGIES:
            self.metadata["distance"] = {"neighbors": self.distance.neighbors, "scaling": "train_median_iqr_constant_range_fallback",
                                         "training_reference": "leave_self_out", "distance_normalizer": self.distance_normalizer,
                                         "forest_normalizer": self.forest_normalizer}
            if self.strategy != "distance":
                self.metadata["distance"].update({"level_columns": self.level_columns, "level_normalizer": self.level_normalizer})
        digest = hashlib.sha256(json.dumps(self.metadata, sort_keys=True).encode()).hexdigest()[:16]
        prefix = "novelty" if self.strategy in DISTANCE_STRATEGIES else "iforest"
        self.metadata["model_id"] = f"{prefix}-{digest}"
        return self

    def score(self, values):
        if getattr(self, "strategy", "joint") in DISTANCE_STRATEGIES:
            return np.max(list(self.score_components(values).values()), axis=0)
        # getattr keeps the first archived joint-model artifact loadable.
        if getattr(self, "strategy", "joint") == "joint":
            return -self.estimator.score_samples(values)
        scores = [
            (-estimator.score_samples(values[:, columns]) - center) / scale
            for columns, estimator, center, scale in self.group_models.values()
        ]
        return np.max(scores, axis=0)

    def score_components(self, values):
        components = {"window_distance": self.distance.score(values) / self.distance_normalizer}
        if self.strategy != "distance":
            components["isolation_forest"] = -self.estimator.score_samples(values) / self.forest_normalizer
            components["level_distance"] = self.level_distance.score(values[:, self.level_columns]) / self.level_normalizer
        if not all(np.isfinite(value).all() for value in components.values()):
            raise ValueError("异常分数溢出，请检查读数幅值")
        return components

    def confirm_scores(self, scores, batch, request):
        if getattr(self, "strategy", "joint") != "hybrid_persistent":
            return scores
        # Causal 2-of-3 confirmation. Never carry evidence across gaps or missing data.
        result, history, previous = [], deque(maxlen=3), None
        for index, score in zip(batch.indices, scores):
            if previous is None or index != previous + 1 or (
                request.readings[index].timestamp - request.readings[previous].timestamp
            ).total_seconds() / 60 > self.config.max_gap_minutes:
                history.clear()
            history.append(float(score))
            result.append(sorted([0.0] * (3 - len(history)) + list(history))[1])
            previous = index
        return np.asarray(result)

    def predict(self, request: ColdchainRequest) -> AnomalyResult:
        if self.metadata is None:
            raise ValueError("模型尚未训练")
        batch = extract_features(request, self.config, self.metadata["feature_version"])
        points = [AnomalyPoint(timestamp=r.timestamp, status=status) for r, status in zip(request.readings, batch.statuses)]
        if len(batch.values):
            components = self.score_components(batch.values) if getattr(self, "strategy", "joint") in DISTANCE_STRATEGIES else {}
            raw_scores = np.max(list(components.values()), axis=0) if components else self.score(batch.values)
            scores = self.confirm_scores(raw_scores, batch, request)
            local_references = self.distance.references(batch.values) if getattr(self, "strategy", "joint") in DISTANCE_STRATEGIES else None
            level_references = self.level_distance.references(batch.values[:, self.level_columns]) if "level_distance" in components else None
            for position, (row, index, score, raw_score) in enumerate(zip(batch.values, batch.indices, scores, raw_scores)):
                point = points[index]
                point.score = float(score)
                point.raw_score = float(raw_score)
                point.score_components = {name: float(value[position]) for name, value in components.items()}
                point.anomaly = bool(score > self.threshold)
                if point.anomaly:
                    low, high = (local_references[0][position], local_references[1][position]) if local_references is not None else (self.reference_low, self.reference_high)
                    if level_references is not None and max(point.score_components, key=point.score_components.get) == "level_distance":
                        # Show the nearest normal level references when that branch dominates.
                        low, high = row.copy(), row.copy()
                        low[self.level_columns], high[self.level_columns] = level_references[0][position], level_references[1][position]
                    deviations = np.maximum(low - row, row - high) / self.reference_scale
                    for feature in np.argsort(-deviations)[:3]:
                        if deviations[feature] <= 0:
                            continue
                        point.evidence.append(FeatureEvidence(
                            feature=self.names[feature], value=float(row[feature]),
                            reference_low=float(low[feature]), reference_high=float(high[feature]),
                            interpretation="above_reference" if row[feature] > high[feature] else "below_reference",
                            reference_type="normal_neighbors" if local_references is not None else "training_percentiles",
                        ))
        return AnomalyResult(
            batch_id=request.batch_id, model_id=self.metadata["model_id"], feature_version=self.metadata["feature_version"],
            algorithm=self.metadata.get("algorithm", "isolation_forest"), confirmation_points=self.metadata.get("confirmation_points", 1),
            model_strategy=self.metadata.get("strategy", "joint"),
            feature_config=self.config, training_source=self.metadata["training_source"],
            input_source=request.readings[0].source,
            source_matches_training=request.readings[0].source == self.metadata["training_source"],
            threshold=self.threshold, threshold_method=self.metadata["threshold_method"], feature_names=self.names,
            scored_count=len(batch.indices), anomaly_count=sum(p.anomaly is True for p in points), points=points,
        )

    def save(self, directory: Path):
        if self.metadata is None:
            raise ValueError("模型尚未训练")
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / "model.joblib"
        joblib.dump(self, path)
        manifest = {**self.metadata, "model_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
        (directory / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    @classmethod
    def load(cls, directory: Path):
        # Local administrator-created artifacts only. Never accept an HTTP-supplied path.
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        if manifest["sklearn_version"] != sklearn.__version__ or manifest["feature_version"] not in {FEATURE_VERSION, DISTANCE_FEATURE_VERSION}:
            raise ValueError("模型依赖/特征版本不匹配，请在当前环境重新训练")
        path = directory / "model.joblib"
        if hashlib.sha256(path.read_bytes()).hexdigest() != manifest["model_sha256"]:
            raise ValueError("模型文件校验失败")
        detector = joblib.load(path)
        if not isinstance(detector, cls) or detector.metadata != {k: v for k, v in manifest.items() if k != "model_sha256"}:
            raise ValueError("模型与清单不匹配")
        return detector
