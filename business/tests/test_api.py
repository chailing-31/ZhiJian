import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.analyze_csv import load_csv
from app.main import app
from app.rules import check_coldchain


client = TestClient(app)
SAMPLES = Path(__file__).resolve().parents[1] / "samples"


def payload(temperatures, minutes=None, batch_id=1):
    start = datetime(2026, 10, 3, 10, tzinfo=timezone(timedelta(hours=8)))
    minutes = list(range(len(temperatures))) if minutes is None else minutes
    return {"batch_id": batch_id, "readings": [
        {"timestamp": (start + timedelta(minutes=minute)).isoformat(),
         "temperature": temperature, "humidity": 85, "source": "simulation"}
        for temperature, minute in zip(temperatures, minutes)
    ]}


def check(body):
    response = client.post("/coldchain/check", json=body)
    assert response.status_code == 200, response.text
    return response.json()


def test_health():
    assert client.get("/health").json()["status"] == "ok"


@pytest.mark.parametrize("temperatures,status", [
    ([4, 7], "normal"), ([8] * 6, "normal"),
    ([12], "insufficient_data"), ([12] * 5, "pending"),
])
def test_non_alert_states(temperatures, status):
    result = check(payload(temperatures))
    assert result["status"] == status
    assert result["alert"] is False
    assert result["episodes"] == []


def test_exact_duration_and_repeat():
    body = payload([12] * 6)
    result = check(body)
    assert result == check(body)
    assert result["alert"] is True
    assert result["level"] == "HIGH"
    assert len(result["episodes"]) == 1
    episode = result["episodes"][0]
    assert episode["triggered_at"] == "2026-10-03T10:05:00+08:00"
    assert episode["observed_minutes"] == 5
    assert episode["trigger_value"] == 12


def test_irregular_sampling_uses_time_not_point_count():
    result = check(payload([10, 11, 13, 14], [0, 2, 4, 6]))
    assert result["alert"] is True
    episode = result["episodes"][0]
    assert episode["triggered_at"] == "2026-10-03T10:06:00+08:00"
    assert episode["trigger_value"] == 14


def test_short_excursion_resets_timer():
    result = check(payload([12, 12, 4, 12, 12, 12, 12]))
    assert result["status"] == "pending"
    assert result["episodes"] == []


def test_recovery_retains_history():
    result = check(payload([12] * 6 + [4]))
    assert result["alert"] is False
    episode = result["episodes"][0]
    assert episode["end_reason"] == "recovered"
    assert episode["recovered_at"] == "2026-10-03T10:06:00+08:00"


def test_gaps_do_not_count_as_continuity():
    result = check(payload([12, 12], [0, 20]))
    assert result["alert"] is False
    assert result["data_gap_count"] == 1


def test_gap_after_alert_is_not_a_recovery():
    result = check(payload([12] * 6 + [4], [0, 1, 2, 3, 4, 5, 20]))
    episode = result["episodes"][0]
    assert episode["end_reason"] == "data_gap"
    assert episode["recovered_at"] is None


def test_separate_episodes():
    result = check(payload([12] * 6 + [4] + [12] * 6))
    assert result["alert"] is True
    assert [e["end_reason"] for e in result["episodes"]] == ["recovered", "ongoing"]


def test_batches_and_requests_do_not_share_state():
    assert check(payload([12] * 6))["alert"] is True
    for batch_id in [1, 2]:
        result = check(payload([12], batch_id=batch_id))
        assert result["batch_id"] == batch_id
        assert result["status"] == "insufficient_data"


@pytest.mark.parametrize("minutes", [[1, 0], [0, 0]])
def test_bad_order_rejected(minutes):
    assert client.post("/coldchain/check", json=payload([12, 12], minutes)).status_code == 422


def test_equivalent_timezones_rejected_as_duplicate():
    body = payload([12, 12])
    body["readings"][0]["timestamp"] = "2026-10-03T02:01:00Z"
    assert client.post("/coldchain/check", json=body).status_code == 422


@pytest.mark.parametrize("batch_id", ["APPLE-2026-001", "1", 0, -1, True, 1.5, 9223372036854775808])
def test_numeric_database_id_required(batch_id):
    assert client.post("/coldchain/check", json=payload([4], batch_id=batch_id)).status_code == 422


@pytest.mark.parametrize("field,value", [
    ("humidity", 101), ("humidity", -1), ("temperature", "NaN"),
    ("temperature", "Infinity"), ("timestamp", "2026-10-03T10:00:00"),
    ("timestamp", 1790983200), ("timestamp", "1790983200"), ("source", "unknown"),
    ("equipment_current", -1), ("door_open", "false"),
])
def test_invalid_reading_rejected(field, value):
    body = payload([4])
    body["readings"][0][field] = value
    assert client.post("/coldchain/check", json=body).status_code == 422


@pytest.mark.parametrize("config", [
    {"duration_minutes": 0}, {"max_gap_minutes": 0}, {"temperature_upper": "NaN"},
])
def test_invalid_config_rejected(config):
    body = payload([4]); body["config"] = config
    assert client.post("/coldchain/check", json=body).status_code == 422


@pytest.mark.parametrize("field", ["temperature", "humidity", "equipment_current"])
@pytest.mark.parametrize("value", [False, True])
def test_boolean_reading_numbers_rejected(field, value):
    body = payload([4])
    body["readings"][0][field] = value
    response = client.post("/coldchain/check", json=body)
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "readings", 0, field]


@pytest.mark.parametrize("field", ["temperature_upper", "duration_minutes", "max_gap_minutes"])
@pytest.mark.parametrize("value", [False, True])
def test_boolean_config_numbers_rejected(field, value):
    body = payload([4])
    body["config"] = {field: value}
    response = client.post("/coldchain/check", json=body)
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "config", field]


@pytest.mark.parametrize("field", ["temperature", "humidity", "material_temperature"])
@pytest.mark.parametrize("value", [False, True])
def test_boolean_processing_numbers_rejected(field, value):
    body = {"batch_id": 1, "grade": "A", "temperature": 20, "humidity": 80}
    body[field] = value
    response = client.post("/business/process-advice", json=body)
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", field]


def test_boolean_temperature_cannot_recover_an_alert():
    assert check(payload([12] * 6))["alert"] is True
    response = client.post("/coldchain/check", json=payload([12] * 6 + [False]))
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "readings", 6, "temperature"]


@pytest.mark.parametrize("endpoint", ["/coldchain/check", "/coldchain/analyze", "/business/process-advice"])
@pytest.mark.parametrize("token", ["1e309", "-1e309", "NaN", "Infinity"])
def test_nonfinite_number_returns_serializable_validation_error(endpoint, token):
    body = (payload([4]) if endpoint.startswith("/coldchain/") else
            {"batch_id": 1, "grade": "A", "temperature": 4, "humidity": 80})
    # Send raw JSON: the HTTP client's json= serializer refuses NaN/Infinity.
    content = json.dumps(body).replace('"temperature": 4', '"temperature": ' + token)
    response = client.post(endpoint, content=content, headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    result = response.json()
    assert result["detail"][0]["type"] == "finite_number"
    assert result["detail"][0]["loc"][-1] == "temperature"
    json.dumps(result, allow_nan=False)


def test_nested_nonfinite_invalid_input_returns_422():
    body = payload([4])
    body["readings"][0]["temperature"] = {"invalid": [float("inf"), float("nan")]}
    response = client.post("/coldchain/check", content=json.dumps(body),
                           headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    error = response.json()["detail"][0]
    assert error["loc"] == ["body", "readings", 0, "temperature"]
    assert error["input"] == {"invalid": ["inf", "nan"]}


@pytest.mark.parametrize("timestamp", ["9999-12-31T23:59:59-12:00", "0001-01-01T00:00:00+14:00"])
def test_timezone_overflow_returns_validation_error(timestamp):
    body = payload([4])
    body["readings"][0]["timestamp"] = timestamp
    response = client.post("/coldchain/check", json=body)
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "readings", 0, "timestamp"]


def test_empty_and_oversize_history_rejected():
    assert client.post("/coldchain/check", json=payload([])).status_code == 422
    assert client.post("/coldchain/check", json=payload([4] * 10001)).status_code == 422


def test_source_is_required_and_cannot_mix():
    body = payload([4, 5]); body["readings"][1]["source"] = "sensor"
    assert client.post("/coldchain/check", json=body).status_code == 422
    del body["readings"][1]["source"]
    assert client.post("/coldchain/check", json=body).status_code == 422


def test_custom_threshold():
    body = payload([12] * 6); body["config"] = {"temperature_upper": 15}
    assert check(body)["alert"] is False


def test_old_contract_rejected():
    body = payload([4]); body["time"] = body["readings"][0]["timestamp"]
    assert client.post("/coldchain/check", json=body).status_code == 422


@pytest.mark.parametrize("grade", ["A", "B", "C", "REJECT"])
def test_process_advice_is_explicitly_unverified(grade):
    response = client.post("/business/process-advice", json={
        "batch_id": 1, "grade": grade, "temperature": 20, "humidity": 80,
        "material_temperature": 5,
    })
    assert response.status_code == 200
    result = response.json()
    assert result["advice_type"] == "rule"
    assert result["type"] == "rule"
    assert result["demo_only"] and result["requires_confirmation"]
    if grade in {"A", "B"}:
        assert result["status"] == "suggested"
        assert result["advice"]["pre_cooling_time"] == {"A": "4h", "B": "6h"}[grade]
        assert result["advice"]["washing_pressure"] == "normal"
    else:
        assert result["status"] == ("blocked" if grade == "REJECT" else "manual_review")
        assert result["advice"]["pre_cooling_time"] is None
        assert result["advice"]["washing_pressure"] is None
    assert result["advice"]["action"]


def test_sample_json_matches_saved_response():
    for name, endpoint in [("coldchain", "/coldchain/check"), ("processing", "/business/process-advice")]:
        body = json.loads((SAMPLES / f"{name}-request.json").read_text(encoding="utf-8"))
        response = client.post(endpoint, json=body)
        assert response.status_code == 200
        assert response.json() == json.loads((SAMPLES / f"{name}-response.json").read_text(encoding="utf-8"))


@pytest.mark.parametrize("name,alert,end_reason", [
    ("normal", False, None), ("overheat", True, "ongoing"), ("recovery", False, "recovered"),
])
def test_csv_samples(name, alert, end_reason):
    result = check_coldchain(load_csv(SAMPLES / f"{name}.csv", batch_id=17))
    assert result.batch_id == 17
    assert result.alert is alert
    assert result.source == "simulation"
    if end_reason is None:
        assert result.episodes == []
    else:
        assert result.episodes[0].end_reason == end_reason
        minute = "06" if name == "overheat" else "05"
        assert result.episodes[0].triggered_at.isoformat() == f"2026-10-03T10:{minute}:00+08:00"


@pytest.mark.parametrize("content", [
    "time,temperature,humidity,source\n2026-10-03T10:00:00+08:00,4,85,simulation\n",
    "timestamp,temperature,humidity,source\n2026-10-03T10:00:00+08:00,4,85\n",
    "timestamp,temperature,humidity,source\n2026-10-03T10:00:00+08:00,4,85,simulation,extra\n",
    "timestamp,temperature,humidity,source\n",
    "timestamp,temperature,humidity,source,door_open\n2026-10-03T10:00:00+08:00,4,85,simulation,maybe\n",
])
def test_bad_csv_rejected(tmp_path, content):
    path = tmp_path / "bad.csv"; path.write_text(content, encoding="utf-8")
    with pytest.raises(ValueError):
        load_csv(path, batch_id=1)
