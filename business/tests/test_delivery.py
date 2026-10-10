import io
import json
from pathlib import Path
import subprocess
import sys
from urllib.error import HTTPError

from fastapi.testclient import TestClient
import pytest

from app import demo, processing
from app.analyze_csv import load_csv
from app.main import app
from app.models import ProcessRequest, ProcessResponse
from app.rules import check_coldchain, process_advice


ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "samples"


def run_demo(*args):
    return subprocess.run([sys.executable, "-m", "app.demo", *map(str, args)], cwd=ROOT,
                          capture_output=True, encoding="utf-8", timeout=30)


def test_recovery_replay_observes_elapsed_time_and_keeps_history():
    sequence = load_csv(SAMPLES / "recovery.csv", 17)
    results = list(demo.replay(sequence))
    assert [result.status for _, result in results] == [
        "insufficient_data", "pending", "pending", "pending", "pending", "active", "normal"]
    assert results[-1][1].episodes[0].end_reason == "recovered"
    assert results[-1][1] == check_coldchain(sequence)
    assert len(sequence.readings) == 7


def test_gap_replay_does_not_call_missing_data_recovery():
    results = list(demo.replay(load_csv(SAMPLES / "gap.csv", 2)))
    assert results[5][1].alert
    final = results[-1][1]
    assert final.status == "insufficient_data" and not final.alert
    assert final.data_gap_count == 1
    assert final.episodes[0].end_reason == "data_gap"
    assert final.episodes[0].recovered_at is None


@pytest.mark.parametrize("interval", [-1, 11, float("nan"), float("inf")])
def test_bad_pace_fails_before_calculation_or_http(interval, monkeypatch):
    def unexpected(*args, **kwargs):
        pytest.fail("Invalid interval must not invoke calculation/HTTP")
    monkeypatch.setattr(demo, "evaluate", unexpected)
    with pytest.raises(ValueError, match="播放间隔"):
        list(demo.replay(load_csv(SAMPLES / "normal.csv", 1), interval=interval))


def test_http_replay_sends_isolated_prefixes_and_matches_actual_route(monkeypatch):
    sent = []
    with TestClient(app) as client:
        def forward(query, timeout):
            body = json.loads(query.data)
            sent.append(body)
            response = client.post("/coldchain/check", json=body)
            assert response.status_code == 200
            return io.BytesIO(response.content)
        monkeypatch.setattr(demo, "urlopen", forward)
        sequence = load_csv(SAMPLES / "recovery.csv", 12)
        result = list(demo.replay(sequence, base_url="http://127.0.0.1:8001"))[-1][1]
    assert [len(body["readings"]) for body in sent] == list(range(1, 8))
    assert {body["batch_id"] for body in sent} == {12}
    assert result == check_coldchain(sequence)


def test_http_error_stops_at_failed_point_without_skipping(monkeypatch):
    calls = []
    def server(query, timeout):
        body = json.loads(query.data)
        calls.append(len(body["readings"]))
        if len(calls) == 3:
            raise HTTPError(query.full_url, 503, "unavailable", {}, None)
        with TestClient(app) as client:
            return io.BytesIO(client.post("/coldchain/check", json=body).content)
    monkeypatch.setattr(demo, "urlopen", server)
    with pytest.raises(ValueError, match="503"):
        list(demo.replay(load_csv(SAMPLES / "recovery.csv", 1), base_url="http://127.0.0.1:8001"))
    assert calls == [1, 2, 3]


def test_valid_but_wrong_remote_result_is_not_accepted(monkeypatch):
    request = ProcessRequest.model_validate_json((SAMPLES / "processing-v1-request.json").read_text(encoding="utf-8"))
    wrong = process_advice(request).model_copy(update={"rule_version": "other-version"})
    monkeypatch.setattr(demo, "urlopen", lambda *args, **kwargs: io.BytesIO(wrong.model_dump_json().encode()))
    with pytest.raises(ValueError, match="不一致"):
        demo.evaluate("/business/process-advice", request, ProcessResponse, process_advice, "http://127.0.0.1:8001")


def test_cli_output_is_utf8_json_with_correct_state_progression(tmp_path):
    output = tmp_path / "中文报告.json"
    run = run_demo("coldchain", SAMPLES / "overheat.csv", "--output", output)
    assert run.returncode == 0, run.stderr
    report = json.loads(run.stdout)
    assert report == json.loads(output.read_text(encoding="utf-8"))
    assert [step["status"] for step in report["steps"]] == [
        "normal", "insufficient_data", "pending", "pending", "pending", "pending", "active"]
    assert report["final_result"]["episodes"][0]["triggered_at"] == "2026-10-03T10:06:00+08:00"


def test_invalid_later_csv_row_never_emits_partial_success(tmp_path):
    csv = tmp_path / "invalid.csv"
    csv.write_text((SAMPLES / "recovery.csv").read_text(encoding="utf-8") +
                   "2026-10-03T10:05:00+08:00,4,85,simulation\n", encoding="utf-8")
    output = tmp_path / "should-not-exist.json"
    run = run_demo("coldchain", csv, "--output", output)
    assert run.returncode == 2
    assert not run.stdout and not output.exists()
    assert "严格递增" in run.stderr


def test_cli_will_not_overwrite_existing_report(tmp_path):
    output = tmp_path / "keep.json"
    output.write_text("keep", encoding="utf-8")
    run = run_demo("processing", SAMPLES / "processing-v1-request.json", "--output", output)
    assert run.returncode == 2
    assert output.read_text(encoding="utf-8") == "keep"


def test_processing_cli_is_the_same_word_result_as_api():
    run = run_demo("processing", SAMPLES / "processing-v1-request.json")
    assert run.returncode == 0, run.stderr
    with TestClient(app) as client:
        expected = client.post("/business/process-advice", json=json.loads(
            (SAMPLES / "processing-v1-request.json").read_text(encoding="utf-8"))).json()
    assert json.loads(run.stdout) == expected


@pytest.mark.parametrize("error", [FileNotFoundError("private path"), ValueError("private config")])
def test_config_failure_is_503_and_does_not_break_coldchain(monkeypatch, error):
    def broken():
        raise error
    monkeypatch.setattr(processing, "load_processing_config", broken)
    with TestClient(app) as client:
        response = client.post("/business/process-advice", json={
            "batch_id": "APPLE-2026-001", "grade": "B", "temperature": 5, "humidity": 80})
        assert response.status_code == 503
        assert set(response.json()) == {"detail"}
        assert "private" not in response.text
        sequence = load_csv(SAMPLES / "overheat.csv", 1)
        assert client.post("/coldchain/check", json=sequence.model_dump(mode="json")).json()["alert"]


def test_committed_openapi_matches_current_service():
    assert json.loads((ROOT / "openapi.json").read_text(encoding="utf-8")) == app.openapi()


def test_documented_validation_error_shape():
    with TestClient(app) as client:
        response = client.post("/business/process-advice", json={"batch_id": 1, "temperature": 5, "humidity": 80})
    assert response.status_code == 422
    assert response.json()["detail"][0]["loc"] == ["body", "grade"]
    assert response.json()["detail"][0]["type"] == "missing"
