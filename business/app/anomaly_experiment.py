"""Train only on train, calibrate only on normal calibration, evaluate frozen test."""

import argparse
import csv
import hashlib
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

from .anomaly import AnomalyDetector, STRATEGIES
from .anomaly_dataset import load_dataset
from .anomaly_models import FeatureConfig
from .models import DemoConfig
from .rules import check_coldchain


def metrics(truth, predictions):
    tp = sum(y == 1 and p for y, p in zip(truth, predictions))
    fp = sum(y == 0 and p for y, p in zip(truth, predictions))
    tn = sum(y == 0 and not p for y, p in zip(truth, predictions))
    fn = sum(y == 1 and not p for y, p in zip(truth, predictions))
    return {
        "evaluated_points": len(truth), "tp": tp, "fp": fp, "tn": tn, "fn": fn,
        "precision": tp / (tp + fp) if tp + fp else None,
        "recall": tp / (tp + fn) if tp + fn else None,
        "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
        "false_positive_rate": fp / (fp + tn) if fp + tn else None,
    }


def evaluate(detector, sequences, rule_config, split="test"):
    records, cases = [], []
    delays = {"rule": [], "model": []}
    total_events = 0
    event_cases, sequence_metrics = [], []
    for sequence in sequences:
        if sequence.split != split:
            continue
        request = sequence.request.model_copy(update={"config": rule_config})
        rule = check_coldchain(request)
        model = detector.predict(request)
        predictions = {"rule": [], "model": []}
        for reading, label, point in zip(request.readings, sequence.labels, model.points):
            rule_flag = any(e.triggered_at <= reading.timestamp <= e.last_observed_at for e in rule.episodes)
            predictions["rule"].append(rule_flag)
            predictions["model"].append(point.anomaly is True)
            records.append({
                "sequence_id": sequence.sequence_id, "scenario": sequence.scenario,
                "timestamp": reading.timestamp.isoformat(), "label": label,
                "model_status": point.status, "model_score": point.score,
                "model_raw_score": point.raw_score,
                "model_anomaly": point.anomaly, "rule_alert": rule_flag,
            })
            if point.status == "scored" and ((point.anomaly is True) != bool(label) or rule_flag != bool(label)):
                cases.append({**records[-1], "model_evidence": [e.model_dump() for e in point.evidence]})
        # Each contiguous labelled event is evaluated independently; misses are not zero delay.
        start = None
        sequence_events = []
        for i in range(len(sequence.labels) + 1):
            active = i < len(sequence.labels) and sequence.labels[i] == 1
            if active and start is None:
                start = i
            if not active and start is not None:
                total_events += 1
                event = {"sequence_id": sequence.sequence_id, "scenario": sequence.scenario,
                         "started_at": request.readings[start].timestamp.isoformat(), "points": i - start}
                for method in delays:
                    first = next((j for j in range(start, i) if predictions[method][j]), None)
                    if first is not None:
                        delays[method].append((request.readings[first].timestamp - request.readings[start].timestamp).total_seconds() / 60)
                    event[method] = {"detected": first is not None,
                                     "delay_minutes": delays[method][-1] if first is not None else None}
                event_cases.append(event)
                sequence_events.append(event)
                start = None
        valid = [i for i, point in enumerate(model.points) if point.status == "scored"]
        sequence_metrics.append({
            "sequence_id": sequence.sequence_id, "scenario": sequence.scenario,
            **{method: {**metrics([sequence.labels[i] for i in valid], [predictions[method][i] for i in valid]),
                        "labelled_events": len(sequence_events),
                        "detected_events": sum(e[method]["detected"] for e in sequence_events)} for method in predictions},
        })
    scored = [r for r in records if r["model_status"] == "scored"]
    groups = defaultdict(list)
    for row in scored:
        groups[row["scenario"]].append(row)

    def comparison(rows):
        labels = [r["label"] for r in rows]
        return {
            "rule": metrics(labels, [r["rule_alert"] for r in rows]),
            "model": metrics(labels, [r["model_anomaly"] is True for r in rows]),
        }

    result = {
        "point_comparison_scope": "Only points with a complete model feature window; both methods use identical points.",
        "coverage": {
            "total_points": len(records), "scored_points": len(scored),
            "unscored_points": len(records) - len(scored),
            "unscored_positive_points": sum(r["label"] for r in records if r["model_status"] != "scored"),
        },
        "overall": comparison(scored),
        "by_scenario": {name: comparison(rows) for name, rows in groups.items()},
        "event_detection": {
            method: {
                "labelled_events": total_events, "detected_events": len(values),
                "missed_events": total_events - len(values),
                "event_recall": len(values) / total_events if total_events else None,
                "median_delay_minutes_on_detected_events": float(np.median(values)) if values else None,
                "max_delay_minutes_on_detected_events": max(values) if values else None,
            } for method, values in delays.items()
        },
        "event_cases": event_cases,
        "by_sequence": sequence_metrics,
        "error_case_count": len(cases),
    }
    return result, records, cases


def choose_candidate(reports):
    """A missing fault class must not be hidden by a good aggregate point F1."""
    def quality(report):
        overall = report["overall"]["model"]
        fpr = overall["false_positive_rate"]
        event = report["event_detection"]["model"]
        recalls = [r["model"]["recall"] for r in report["by_scenario"].values() if r["model"]["recall"] is not None]
        return {"false_positive_rate": fpr, "event_recall": event["detected_events"] / event["labelled_events"],
                "worst_scenario_recall": min(recalls) if recalls else 0, "f1": overall["f1"] or 0}

    quality_by_model = {name: quality(report) for name, report in reports.items()}
    eligible = [name for name, q in quality_by_model.items() if q["false_positive_rate"] is not None and q["false_positive_rate"] <= 0.05]
    qualified = [name for name in eligible if quality_by_model[name]["event_recall"] >= 0.9 and quality_by_model[name]["worst_scenario_recall"] >= 0.8]
    if qualified:
        selected = max(qualified, key=lambda name: (quality_by_model[name]["f1"], quality_by_model[name]["event_recall"], quality_by_model[name]["worst_scenario_recall"]))
    elif eligible:
        selected = max(eligible, key=lambda name: (quality_by_model[name]["event_recall"],
                                                   quality_by_model[name]["worst_scenario_recall"], quality_by_model[name]["f1"]))
    else:
        selected = min(quality_by_model, key=lambda name: (quality_by_model[name]["false_positive_rate"]
                                                         if quality_by_model[name]["false_positive_rate"] is not None else 1.0,
                                                         -quality_by_model[name]["event_recall"]))
    return selected, quality_by_model


def select_detector(sequences, config, seed, false_positive_rate, strategy, calibration_fprs=None):
    if strategy == "auto_validation" and not any(s.split == "validation" for s in sequences):
        raise ValueError("模型选择必须提供独立 validation 集，不能用 test 集选择")
    validation = [s for s in sequences if s.split == "validation"]
    if strategy == "auto_validation" and {label for s in validation for label in s.labels} != {0, 1}:
        raise ValueError("自动选型验证集必须包含正常点和标注异常事件")
    choices = list(STRATEGIES) if strategy == "auto_validation" else [strategy]
    rates = list(calibration_fprs) if calibration_fprs is not None else [false_positive_rate]
    if not rates or len(set(rates)) != len(rates) or any(not 0 < rate < 0.5 for rate in rates):
        raise ValueError("校准候选必须非空、不重复，且均在0和0.5之间")
    if len(rates) > 1 and strategy != "auto_validation":
        raise ValueError("多个校准候选必须通过 auto_validation 独立验证选择")
    candidates, validation_reports = {}, {}
    for choice, rate in [(choice, rate) for choice in choices for rate in rates]:
        key = f"{choice}@{rate:g}" if len(rates) > 1 else choice
        detector = AnomalyDetector(config, seed, rate, strategy=choice).fit(
            [s.request for s in sequences if s.split == "train"],
            [s.request for s in sequences if s.split == "calibration"],
        )
        candidates[key] = detector
        if any(s.split == "validation" for s in sequences):
            result, _, _ = evaluate(detector, sequences, DemoConfig(), split="validation")
            validation_reports[key] = result
    quality = {}
    if strategy == "auto_validation":
        selected, quality = choose_candidate(validation_reports)
    else:
        selected = strategy
    selection = {
        "requested_strategy": strategy, "selected_strategy": candidates[selected].strategy,
        "selected_candidate": selected, "selected_calibration_fpr": candidates[selected].target_false_positive_rate,
        "calibration_candidates": rates,
        "selection_rule": "Require validation FPR <= 5%, event recall >= 90%, worst fault-scenario point recall >= 80%; among qualified candidates maximize F1, then event and worst-scenario recall. Otherwise, among FPR-eligible candidates maximize event then worst-scenario recall then F1; if none eligible minimize FPR. Stable candidate order breaks ties. No test metrics used.",
        "validation_targets": {"max_false_positive_rate": 0.05, "min_event_recall": 0.9, "min_worst_scenario_recall": 0.8},
        "validation_target_met": (quality[selected]["false_positive_rate"] is not None
                                  and quality[selected]["false_positive_rate"] <= 0.05
                                  and quality[selected]["event_recall"] >= 0.9
                                  and quality[selected]["worst_scenario_recall"] >= 0.8) if quality else None,
        "validation_quality": quality,
        "validation": validation_reports,
    }
    return candidates[selected], selection


def run_experiment(manifest: Path, output: Path, config: FeatureConfig, seed=42, false_positive_rate=0.02, strategy="joint", calibration_fprs=None):
    if (output / "manifest.json").exists() or (output / "evaluation.json").exists():
        raise ValueError("实验输出已存在，请指定新目录，避免覆盖模型或测试证据")
    sequences = load_dataset(manifest)
    detector, selection = select_detector(sequences, config, seed, false_positive_rate, strategy, calibration_fprs)
    detector.save(output)
    (output / "selection.json").write_text(json.dumps(selection, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rule_config = DemoConfig()
    report, records, cases = evaluate(detector, sequences, rule_config)
    report.update({
        "model": detector.metadata,
        "model_selection": selection,
        "dataset_manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
        "test_fingerprints": [
            {"id": s.sequence_id, "sha256": hashlib.sha256(json.dumps({
                "request": s.request.model_dump(mode="json"), "labels": s.labels, "scenario": s.scenario,
            }, sort_keys=True).encode()).hexdigest()}
            for s in sequences if s.split == "test"
        ],
        "dataset_source": sequences[0].request.readings[0].source,
        "rule_config": rule_config.model_dump(),
        "limitations": [
            "Synthetic injection labels are not validated equipment-fault or food-quality labels.",
            "Test results are not used to select model parameters or the calibration threshold.",
            "Overlapping windows are correlated; point metrics are not independent trials.",
            "The baseline checks sustained high temperature only; model features cover additional variables.",
            "No field deployment or Spring Boot/MySQL/Vue integration was evaluated.",
        ],
    })
    (output / "evaluation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "error-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with (output / "predictions.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(records[0]))
        writer.writeheader(); writer.writerows(records)
    return report


def main():
    parser = argparse.ArgumentParser(description="训练、校准、独立测试冷链异常模型")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", choices=["environment", "device"], default="device")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--calibration-fpr", type=float, default=0.02)
    parser.add_argument("--calibration-grid", type=float, nargs="+", help="仅用独立 validation 在多个正常校准目标之间选择")
    parser.add_argument("--strategy", choices=[*STRATEGIES, "auto_validation"], default="joint")
    args = parser.parse_args()
    try:
        report = run_experiment(args.manifest, args.output, FeatureConfig(profile=args.profile), args.seed, args.calibration_fpr, args.strategy, args.calibration_grid)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, f"实验失败：{error}\n")
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps({"source": report["dataset_source"], "overall": report["overall"], "event_detection": report["event_detection"]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
