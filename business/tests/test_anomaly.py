import json
from datetime import datetime, timedelta

import numpy as np
import pytest
from fastapi.testclient import TestClient

import app.main as api
from app.anomaly import AnomalyDetector, extract_features, STRATEGIES, DISTANCE_FEATURE_VERSION, FeatureBatch
from app.anomaly_dataset import Sequence, generate_dataset, load_dataset
from app.anomaly_experiment import evaluate, metrics, select_detector, choose_candidate
from app.anomaly_models import FeatureConfig
from app.models import ColdchainRequest, DemoConfig, SHANGHAI


def sequence(batch_id=1, count=90):
    rng = np.random.default_rng(batch_id)
    start = datetime(2026, 1, batch_id, tzinfo=SHANGHAI)
    return ColdchainRequest(batch_id=batch_id, readings=[{
        "timestamp": start + timedelta(minutes=i),
        "temperature": float(4 + rng.normal(0, 0.1)), "humidity": float(85 + rng.normal(0, 0.3)),
        "door_open": False, "equipment_current": float(2.2 + rng.normal(0, 0.05)), "source": "simulation",
    } for i in range(count)])


@pytest.fixture(scope="module")
def detector():
    return AnomalyDetector(FeatureConfig()).fit([sequence(1), sequence(2)], [sequence(3)])


def test_features_are_causal():
    request = sequence()
    short = request.model_copy(update={"readings": request.readings[:20]})
    prefix = extract_features(short, FeatureConfig())
    full = extract_features(request, FeatureConfig())
    np.testing.assert_array_equal(prefix.values, full.values[:len(prefix.values)])
    assert prefix.indices[0] == 5
    assert len(prefix.names) == 12


def test_gap_restarts_feature_history():
    request = sequence(count=20)
    for reading in request.readings[10:]:
        reading.timestamp += timedelta(minutes=20)
    features = extract_features(request, FeatureConfig())
    assert features.statuses[10:15] == ["insufficient_history"] * 5
    assert features.statuses[15] == "scored"


def test_missing_device_values_are_not_imputed_to_zero():
    request = sequence(count=20)
    request.readings[10].equipment_current = None
    device = extract_features(request, FeatureConfig())
    assert device.statuses[10] == "missing_device_fields"
    assert device.statuses[11:16] == ["insufficient_history"] * 5
    environment = extract_features(request, FeatureConfig(profile="environment"))
    assert environment.statuses[10] == "scored"
    assert len(environment.names) == 8


def test_irregular_window_uses_timestamps():
    request = sequence(count=8)
    start = request.readings[0].timestamp
    for i, reading in enumerate(request.readings):
        reading.timestamp = start + timedelta(minutes=2*i)
    features = extract_features(request, FeatureConfig())
    assert features.indices[0] == 3


def test_training_rejects_duplicate_or_same_batches():
    with pytest.raises(ValueError, match="重复序列"):
        AnomalyDetector(FeatureConfig()).fit([sequence()], [sequence().model_copy(update={"batch_id": 2})])
    same_batch = sequence(2).model_copy(update={"batch_id": 1})
    with pytest.raises(ValueError, match="批次必须分离"):
        AnomalyDetector(FeatureConfig()).fit([sequence()], [same_batch])


def test_training_requires_sufficient_normal_data():
    with pytest.raises(ValueError, match="64"):
        AnomalyDetector(FeatureConfig()).fit([sequence(1, 20)], [sequence(2, 20)])


def test_training_rejects_missing_device_fields():
    request = sequence(1); request.readings[10].door_open = None
    with pytest.raises(ValueError, match="缺少门状态"):
        AnomalyDetector(FeatureConfig()).fit([request], [sequence(2)])


def test_predictions_are_frozen_and_explain_reference_deviation(detector):
    request = sequence(4)
    for reading in request.readings[30:50]:
        reading.temperature = 50; reading.humidity = 10; reading.equipment_current = 8
    result = detector.predict(request)
    assert result == detector.predict(request)
    assert result.points[0].anomaly is None
    assert result.points[40].anomaly is True
    assert result.points[40].score > result.threshold
    assert result.points[40].evidence
    assert result.training_source == "simulation" and result.demo_only


def test_source_mismatch_is_visible(detector):
    request = sequence(4)
    for reading in request.readings:
        reading.source = "sensor"
    result = detector.predict(request)
    assert result.source_matches_training is False
    assert result.demo_only is True


def test_model_save_load_and_checksum(detector, tmp_path):
    detector.save(tmp_path)
    restored = AnomalyDetector.load(tmp_path)
    assert restored.predict(sequence(4)) == detector.predict(sequence(4))
    with (tmp_path / "model.joblib").open("ab") as stream:
        stream.write(b"changed")
    with pytest.raises(ValueError, match="校验失败"):
        AnomalyDetector.load(tmp_path)


def test_combined_api_keeps_model_and_rule_separate(detector, monkeypatch):
    monkeypatch.setattr(api, "get_anomaly_detector", lambda: detector)
    response = TestClient(api.app).post("/coldchain/analyze", json=sequence(4).model_dump(mode="json"))
    assert response.status_code == 200
    result = response.json()
    assert set(result) == {"rule_result", "model_result"}
    assert result["model_result"]["algorithm"] == "isolation_forest"
    assert result["rule_result"]["rule_version"] == "demo-coldchain-v2"


def test_missing_model_is_not_silent_success(monkeypatch):
    monkeypatch.delenv("BUSINESS_ANOMALY_MODEL_DIR", raising=False)
    api.get_anomaly_detector.cache_clear()
    response = TestClient(api.app).post("/coldchain/analyze", json=sequence().model_dump(mode="json"))
    assert response.status_code == 503
    assert TestClient(api.app).post("/coldchain/check", json=sequence().model_dump(mode="json")).status_code == 200


def test_dataset_split_and_metrics(tmp_path, detector):
    manifest = generate_dataset(tmp_path)
    data = load_dataset(manifest)
    assert [sum(s.split == split for s in data) for split in ["train", "calibration", "test"]] == [12, 4, 24]
    assert all(not any(s.labels) for s in data if s.split != "test")
    report, records, cases = evaluate(detector, data, DemoConfig())
    assert report["overall"]["rule"]["evaluated_points"] == report["overall"]["model"]["evaluated_points"]
    assert report["coverage"]["unscored_points"] > 0
    assert report["event_detection"]["rule"]["labelled_events"] == 15
    assert report["event_detection"]["rule"]["missed_events"] == 12
    assert len(records) > 4000
    content = json.loads(manifest.read_text(encoding="utf-8"))
    content["sequences"][-1]["batch_id"] = content["sequences"][0]["batch_id"]
    manifest.write_text(json.dumps(content), encoding="utf-8")
    with pytest.raises(ValueError, match="批次不能跨"):
        load_dataset(manifest)


def test_metrics_expose_false_positives_and_misses():
    result = metrics([1, 1, 0, 0], [True, False, True, False])
    assert result["tp"] == result["fp"] == result["tn"] == result["fn"] == 1
    assert result["f1"] == result["false_positive_rate"] == 0.5


@pytest.mark.parametrize("strategy,group_count", [("grouped", 4), ("grouped_levels", 7)])
def test_grouped_model_and_calibration(tmp_path, strategy, group_count):
    grouped = AnomalyDetector(FeatureConfig(), strategy=strategy).fit([sequence(1), sequence(2)], [sequence(3)])
    assert len(grouped.group_models) == group_count
    assert grouped.metadata["calibration_observed_false_positive_rate"] <= 0.02
    grouped.save(tmp_path)
    assert AnomalyDetector.load(tmp_path).predict(sequence(4)) == grouped.predict(sequence(4))
    assert grouped.predict(sequence(4)).model_strategy == strategy


def test_selection_does_not_access_test_data():
    class UnreadableTest:
        split = "test"

        @property
        def request(self):
            raise AssertionError("selection must not read test data")

    validation = sequence(3)
    for reading in validation.readings[30:50]:
        reading.temperature += 3
    sequences = [
        Sequence("train", "train", "normal", sequence(1), [0] * 90),
        Sequence("cal", "calibration", "normal", sequence(2), [0] * 90),
        Sequence("val", "validation", "overheat", validation, [int(30 <= i < 50) for i in range(90)]),
        UnreadableTest(),
    ]
    detector, report = select_detector(sequences, FeatureConfig(), 42, 0.02, "auto_validation")
    assert set(report["validation"]) == set(STRATEGIES)
    assert detector.metadata["strategy"] == report["selected_strategy"]


def test_auto_selection_requires_validation():
    with pytest.raises(ValueError, match="独立 validation"):
        select_detector([], FeatureConfig(), 42, 0.02, "auto_validation")


@pytest.fixture(scope="module")
def distance_detector():
    return AnomalyDetector(FeatureConfig(), strategy="hybrid_distance").fit([sequence(1), sequence(2)], [sequence(3)])


def test_distance_features_include_instantaneous_equipment_and_remain_causal():
    request = sequence()
    prefix = request.model_copy(update={"readings": request.readings[:20]})
    short = extract_features(prefix, FeatureConfig(), DISTANCE_FEATURE_VERSION)
    full = extract_features(request, FeatureConfig(), DISTANCE_FEATURE_VERSION)
    assert full.names[-2:] == ["door_open_current", "equipment_current"]
    np.testing.assert_array_equal(short.values, full.values[:len(short.values)])


@pytest.mark.parametrize("field,value", [("temperature", 12), ("temperature", -4), ("humidity", 50), ("equipment_current", 0)])
def test_distance_detects_single_channel_plateaus(distance_detector, field, value):
    request = sequence(4)
    for reading in request.readings[25:60]:
        setattr(reading, field, value)
    result = distance_detector.predict(request)
    assert all(point.anomaly for point in result.points[35:55])
    assert result.algorithm == "isolation_forest_knn"
    assert result.points[40].evidence[0].reference_type == "normal_neighbors"
    # Prediction never adapts the normal manifold to an ongoing fault.
    assert result == distance_detector.predict(request)


def test_distance_extrapolates_beyond_normal_tree_range(distance_detector):
    values = extract_features(sequence(4), FeatureConfig(), DISTANCE_FEATURE_VERSION).values[:1]
    far = values.copy(); far[0, 0] = 20
    further = far.copy(); further[0, 0] = 40
    assert distance_detector.score(further)[0] > distance_detector.score(far)[0] > distance_detector.score(values)[0]


def test_distance_normalization_handles_constant_columns():
    from app.novelty_distance import NormalDistance
    values = np.ones((64, 3))
    distance = NormalDistance().fit(values)
    assert np.isfinite(distance.scale).all()
    np.testing.assert_array_equal(distance.score(values[:2]), [0, 0])
    assert distance.score(np.array([[1, 2, 1]]))[0] > 0


def test_temporal_confirmation_is_causal_and_resets():
    detector = AnomalyDetector(FeatureConfig(), strategy="hybrid_persistent")
    request = sequence(count=30)
    indices = [5, 6, 7, 8, 15, 16]
    batch = FeatureBatch(np.empty((6, 0)), indices, [], [])
    scores = np.array([10., 0., 10., 10., 10., 10.])
    np.testing.assert_array_equal(detector.confirm_scores(scores, batch, request), [0, 0, 10, 10, 0, 10])
    prefix = FeatureBatch(np.empty((3, 0)), indices[:3], [], [])
    np.testing.assert_array_equal(detector.confirm_scores(scores[:3], prefix, request), [0, 0, 10])


@pytest.mark.parametrize("strategy", ["distance", "hybrid_distance", "hybrid_persistent"])
def test_new_strategies_save_load_and_temporal_calibration(tmp_path, strategy):
    model = AnomalyDetector(FeatureConfig(), strategy=strategy).fit([sequence(1), sequence(2)], [sequence(3)])
    model.save(tmp_path)
    restored = AnomalyDetector.load(tmp_path)
    assert restored.predict(sequence(4)) == model.predict(sequence(4))
    calibration = model.predict(sequence(3))
    assert sum(p.anomaly is True for p in calibration.points) / calibration.scored_count <= 0.02
    prefix = sequence(4).model_copy(update={"readings": sequence(4).readings[:40]})
    assert restored.predict(prefix).points == restored.predict(sequence(4)).points[:40]


def test_selection_prioritizes_event_coverage_over_aggregate_f1():
    def report(detected, recall, f1, fpr):
        return {"overall": {"model": {"false_positive_rate": fpr, "f1": f1}},
                "event_detection": {"model": {"detected_events": detected, "labelled_events": 10}},
                "by_scenario": {"fault": {"model": {"recall": recall}}}}
    reports = {"missed_class": report(5, 0.9, 0.95, 0.01), "coverage": report(10, 0.8, 0.85, 0.03),
               "noisy": report(10, 1, 1, 0.2)}
    assert choose_candidate(reports)[0] == "coverage"


def test_auto_selection_rejects_normal_only_validation():
    data = [Sequence("v", "validation", "normal", sequence(1), [0] * 90)]
    with pytest.raises(ValueError, match="正常点和标注异常"):
        select_detector(data, FeatureConfig(), 42, 0.02, "auto_validation")


def test_distance_environment_profile_and_missing_device_state():
    request = sequence(4)
    for reading in request.readings:
        reading.door_open = None; reading.equipment_current = None
    detector = AnomalyDetector(FeatureConfig(profile="environment"), strategy="distance").fit([sequence(1)], [sequence(2)])
    assert detector.predict(request).scored_count == 85
    device = AnomalyDetector(FeatureConfig(), strategy="distance").fit([sequence(1)], [sequence(2)])
    assert device.predict(request).scored_count == 0


def test_hybrid_components_and_instantaneous_level_evidence(distance_detector):
    request = sequence(4)
    for reading in request.readings[25:60]:
        reading.temperature += 2
    result = distance_detector.predict(request)
    point = result.points[40]
    assert set(point.score_components) == {"window_distance", "isolation_forest", "level_distance"}
    assert point.score == point.raw_score == max(point.score_components.values())
    assert point.anomaly is True


def test_hybrid_api_exposes_algorithm_and_components(distance_detector, monkeypatch):
    monkeypatch.setattr(api, "get_anomaly_detector", lambda: distance_detector)
    response = TestClient(api.app).post("/coldchain/analyze", json=sequence(4).model_dump(mode="json"))
    assert response.status_code == 200
    result = response.json()["model_result"]
    assert result["algorithm"] == "isolation_forest_knn"
    assert result["feature_version"] == DISTANCE_FEATURE_VERSION
    assert result["points"][10]["score_components"]["level_distance"] >= 0


def test_selection_balances_precision_after_all_coverage_targets_met():
    def report(recall, f1, fpr):
        return {"overall": {"model": {"false_positive_rate": fpr, "f1": f1}},
                "event_detection": {"model": {"detected_events": 10, "labelled_events": 10}},
                "by_scenario": {"fault": {"model": {"recall": recall}}}}
    reports = {"noisy": report(0.99, 0.8, 0.05), "balanced": report(0.9, 0.92, 0.02)}
    assert choose_candidate(reports)[0] == "balanced"


def test_bootstrap_resamples_whole_paired_sequences():
    from app.anomaly_benchmark import aggregate, paired_bootstrap
    previous = {"tp": 0, "fp": 0, "tn": 10, "fn": 10, "labelled_events": 1, "detected_events": 0}
    optimized = {"tp": 10, "fp": 0, "tn": 10, "fn": 0, "labelled_events": 1, "detected_events": 1}
    rows = [{"scenario": "fault", "previous": previous, "optimized": optimized} for _ in range(3)]
    assert aggregate([optimized] * 3)["tp"] == 30
    intervals = paired_bootstrap(rows, repetitions=20)["intervals"]
    assert intervals["paired_change"]["recall"] == [1, 1]
    assert intervals["paired_change"]["false_positive_rate"] == [0, 0]


def test_calibration_grid_requires_independent_selection():
    with pytest.raises(ValueError, match="auto_validation"):
        select_detector([], FeatureConfig(), 42, 0.02, "joint", [0.005, 0.02])
