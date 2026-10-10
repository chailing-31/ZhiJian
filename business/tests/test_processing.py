import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.main import app
from app.processing import ProcessingConfig, load_processing_config


client = TestClient(app)


def request(**overrides):
    return {"batch_id": "APPLE-2026-001", "grade": "B",
            "temperature": 5, "humidity": 80, **overrides}


def advice(**overrides):
    response = client.post("/business/process-advice", json=request(**overrides))
    assert response.status_code == 200, response.text
    return response.json()


def test_word_v1_example_works_without_extra_fields():
    result = advice()
    assert result["batch_id"] == "APPLE-2026-001"
    assert result["type"] == result["advice_type"] == "rule"
    assert result["advice"]["pre_cooling_time"] == "6h"
    assert result["advice"]["washing_pressure"] == "normal"
    assert result["status"] == "suggested"
    assert result["rule_id"] == "PROCESS-B"
    assert result["rule_version"] == "demo-process-v3"
    assert "等级B" in result["reason"]
    assert "原料温度未提供" in result["reason"]
    assert "未经生产验证" in result["rule_basis"]
    assert result["demo_only"] and result["requires_confirmation"]


@pytest.mark.parametrize("batch_id", [1, 9223372036854775807, "1", "A" * 64])
def test_batch_identifier_type_preserved(batch_id):
    result = advice(batch_id=batch_id)
    assert type(result["batch_id"]) is type(batch_id)
    assert result["batch_id"] == batch_id


@pytest.mark.parametrize("batch_id", [True, False, 1.5, 0, -1, 9223372036854775808,
                                      "", "A" * 65, "a b", "A/B", "批次1", "A\n"])
def test_invalid_batch_identifier_rejected(batch_id):
    assert client.post("/business/process-advice", json=request(batch_id=batch_id)).status_code == 422


@pytest.mark.parametrize("field,values", [
    ("temperature", (-0.001, 0, 30, 30.001)),
    ("humidity", (39.999, 40, 95, 95.001)),
    ("material_temperature", (-0.001, 0, 30, 30.001)),
])
def test_applicability_bounds_are_inclusive(field, values):
    for value, expected in zip(values, ("manual_review", "suggested", "suggested", "manual_review")):
        result = advice(**{field: value})
        assert result["status"] == expected
        if expected == "manual_review":
            assert result["advice"]["pre_cooling_time"] is None
            assert result["advice"]["washing_pressure"] is None
            assert result["rule_id"] == "PROCESS-B-OUT-OF-SCOPE"
            assert "超出演示适用范围" in result["reason"]


def test_all_unmatched_conditions_reported_and_no_clamping():
    result = advice(temperature=31, humidity=39, material_temperature=-2)
    assert result["status"] == "manual_review"
    for text in ("环境温度31℃超出", "环境湿度39%RH超出", "原料温度-2℃超出"):
        assert text in result["reason"]
    assert result["advice"]["pre_cooling_time"] is None


@pytest.mark.parametrize("grade,status", [("C", "manual_review"), ("REJECT", "blocked")])
def test_review_and_reject_override_environment(grade, status):
    for temperature in (5, 35):
        result = advice(grade=grade, temperature=temperature)
        assert result["status"] == status
        assert result["advice"]["pre_cooling_time"] is None
        assert result["advice"]["washing_pressure"] is None
        assert result["demo_only"] and result["requires_confirmation"]


def test_environment_is_a_scope_check_not_a_time_prediction():
    first = advice(temperature=0, humidity=40, material_temperature=1)
    second = advice(temperature=30, humidity=95, material_temperature=30)
    assert first["advice"] == second["advice"]
    assert "原料温度30℃" in second["reason"]
    assert "最优时长" in second["reason"]


def test_no_advice_leaks_between_calls():
    assert advice(grade="A")["advice"]["pre_cooling_time"] == "4h"
    assert advice(grade="REJECT")["advice"]["pre_cooling_time"] is None
    assert advice(grade="B", temperature=31)["advice"]["pre_cooling_time"] is None
    assert advice(grade="B", batch_id="APPLE-2026-002")["advice"]["pre_cooling_time"] == "6h"


@pytest.mark.parametrize("field", ["batch_id", "grade", "temperature", "humidity"])
def test_required_field_missing_is_not_a_parameter_suggestion(field):
    body = request()
    del body[field]
    response = client.post("/business/process-advice", json=body)
    assert response.status_code == 422


@pytest.mark.parametrize("changes", [{"grade": "D"}, {"grade": "b"}, {"humidity": 101},
                                      {"material_temperature": "Infinity"}, {"unexpected": 1}])
def test_invalid_processing_input_is_rejected(changes):
    response = client.post("/business/process-advice", json=request(**changes))
    assert response.status_code == 422


@pytest.mark.parametrize("change", ["reversed_range", "missing_grade", "reject_suggested",
                                    "incomplete_advice", "manual_with_values", "duplicate_id",
                                    "humidity_range"])
def test_inconsistent_rule_configuration_rejected(change):
    config = load_processing_config().model_dump()
    if change == "reversed_range":
        config["temperature"]["minimum"] = 31
    elif change == "missing_grade":
        del config["grades"]["C"]
    elif change == "reject_suggested":
        config["grades"]["REJECT"].update(status="suggested", pre_cooling_time="6h", washing_pressure="normal")
    elif change == "incomplete_advice":
        config["grades"]["A"]["pre_cooling_time"] = None
    elif change == "manual_with_values":
        config["grades"]["C"]["washing_pressure"] = "normal"
    elif change == "duplicate_id":
        config["grades"]["A"]["rule_id"] = config["grades"]["B"]["rule_id"]
    else:
        config["humidity"]["maximum"] = 101
    with pytest.raises(ValidationError):
        ProcessingConfig.model_validate(config)


def test_saved_processing_scenarios_match_api():
    path = Path(__file__).resolve().parents[1] / "samples" / "processing-cases.json"
    for case in json.loads(path.read_text(encoding="utf-8")):
        response = client.post("/business/process-advice", json=case["request"])
        assert response.status_code == 200, case["name"]
        assert response.json() == case["response"], case["name"]
