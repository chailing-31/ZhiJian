"""Freeze validation-selected models, then compare on multiple untouched corpora."""

import argparse
import csv
import hashlib
import json
from pathlib import Path

import numpy as np

from .anomaly import AnomalyDetector, request_fingerprint
from .anomaly_dataset import load_dataset
from .anomaly_experiment import evaluate, select_detector
from .anomaly_models import FeatureConfig
from .models import DemoConfig


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def aggregate(rows):
    tp, fp, tn, fn = (sum(r[k] for r in rows) for k in ("tp", "fp", "tn", "fn"))
    events, detected = (sum(r[k] for r in rows) for k in ("labelled_events", "detected_events"))
    return {"tp": tp, "fp": fp, "tn": tn, "fn": fn,
            "precision": tp / (tp + fp) if tp + fp else None,
            "recall": tp / (tp + fn) if tp + fn else None,
            "f1": 2 * tp / (2 * tp + fp + fn) if 2 * tp + fp + fn else None,
            "false_positive_rate": fp / (fp + tn) if fp + tn else None,
            "labelled_events": events, "detected_events": detected,
            "event_recall": detected / events if events else None}


def paired_bootstrap(rows, seed=42, repetitions=1000):
    # Sample independent sequences within scenario, never overlapping points.
    rng = np.random.default_rng(seed)
    scenarios = sorted({row["scenario"] for row in rows})
    groups = [[i for i, row in enumerate(rows) if row["scenario"] == scenario] for scenario in scenarios]
    metrics = ("recall", "f1", "false_positive_rate", "event_recall")
    samples = {method: {metric: [] for metric in metrics} for method in ("previous", "optimized", "paired_change")}
    for _ in range(repetitions):
        indices = np.concatenate([rng.choice(group, size=len(group), replace=True) for group in groups])
        values = {method: aggregate([rows[int(i)][method] for i in indices]) for method in ("previous", "optimized")}
        for metric in metrics:
            a, b = values["previous"][metric], values["optimized"][metric]
            if a is not None and b is not None:
                samples["previous"][metric].append(a)
                samples["optimized"][metric].append(b)
                samples["paired_change"][metric].append(b - a)
    return {"method": "paired scenario-stratified sequence bootstrap, percentile 95% intervals",
            "seed": seed, "repetitions": repetitions,
            "limitation": "Conditional on this synthetic generator and scenario mix; not real-world confidence bounds.",
            "intervals": {method: {metric: np.quantile(values, [0.025, 0.975]).tolist() if values else None
                                    for metric, values in results.items()} for method, results in samples.items()}}


def run_benchmark(development_manifest, test_manifests, output):
    if output.exists():
        raise ValueError("基准实验目录已存在，请使用新目录保留实验记录")
    development = load_dataset(development_manifest)
    optimized, selection = select_detector(development, FeatureConfig(), 42, 0.02, "auto_validation", [0.005, 0.01, 0.02])
    previous = AnomalyDetector(FeatureConfig(), strategy="grouped_levels").fit(
        [s.request for s in development if s.split == "train"],
        [s.request for s in development if s.split == "calibration"],
    )
    output.mkdir(parents=True)
    models = {"previous": previous, "optimized": optimized}
    # Persist the selection and both fixed artifacts before reading any new test CSV.
    for name, model in models.items():
        model.save(output / name)
    write_json(output / "selection.json", selection)
    freeze = {"development_manifest_sha256": hashlib.sha256(development_manifest.read_bytes()).hexdigest(),
              "models": {name: json.loads((output / name / "manifest.json").read_text(encoding="utf-8")) for name in models},
              "code_sha256": {name: hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()
                              for name in ("anomaly.py", "novelty_distance.py", "anomaly_experiment.py", "anomaly_benchmark.py")},
              "test_manifests_planned": [str(path) for path in test_manifests]}
    write_json(output / "frozen-before-test.json", freeze)
    seen = {request_fingerprint(s.request) for s in development}
    corpora, paired_rows, scenarios = [], [], {}
    for ordinal, manifest in enumerate(test_manifests):
        data = [s for s in load_dataset(manifest) if s.split == "test"]
        fingerprints = [request_fingerprint(s.request) for s in data]
        if seen.intersection(fingerprints):
            raise ValueError("测试序列与开发集或其他测试集重复")
        seen.update(fingerprints)
        directory = output / f"test-{ordinal + 1}"
        directory.mkdir()
        reports = {}
        for name, model in models.items():
            report, records, cases = evaluate(model, data, DemoConfig())
            reports[name] = report
            write_json(directory / f"{name}-evaluation.json", report)
            write_json(directory / f"{name}-errors.json", cases)
            with (directory / f"{name}-predictions.csv").open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(records[0]))
                writer.writeheader(); writer.writerows(records)
        if reports["previous"]["coverage"] != reports["optimized"]["coverage"]:
            raise ValueError("两个模型评分覆盖不同，需要显式统一点比较范围")
        for before, after in zip(reports["previous"]["by_sequence"], reports["optimized"]["by_sequence"]):
            if before["sequence_id"] != after["sequence_id"]:
                raise ValueError("配对序列不一致")
            row = {"corpus": ordinal + 1, "sequence_id": before["sequence_id"], "scenario": before["scenario"],
                   "previous": before["model"], "optimized": after["model"]}
            paired_rows.append(row)
            scenarios.setdefault(before["scenario"], []).append(row)
        manifest_data = json.loads(manifest.read_text(encoding="utf-8"))
        corpora.append({"manifest": str(manifest), "manifest_sha256": hashlib.sha256(manifest.read_bytes()).hexdigest(),
                        "seed": manifest_data.get("seed"), "source": manifest_data["source"],
                        "test_fingerprints": [{"id": s.sequence_id, "readings_sha256": fp,
                                               "labels_sha256": hashlib.sha256(json.dumps(s.labels).encode()).hexdigest()} for s, fp in zip(data, fingerprints)],
                        "coverage": reports["optimized"]["coverage"],
                        "results": {name: {"overall": r["overall"], "events": r["event_detection"]} for name, r in reports.items()}})
    summary = {"source": corpora[0]["source"], "selection": selection, "frozen": freeze,
               "corpora": corpora, "sequence_count": len(paired_rows),
               "overall": {method: aggregate([r[method] for r in paired_rows]) for method in models},
               "by_scenario": {scenario: {method: aggregate([r[method] for r in rows]) for method in models} for scenario, rows in scenarios.items()},
               "bootstrap": paired_bootstrap(paired_rows),
               "limitations": ["All scenarios are synthetic; no real sensor performance is established.",
                               "Both models use the same normal training and calibration sequences; all test corpora are new.",
                               "Old validation was used during development; held-out tests are evaluated only after selection is frozen.",
                               "Recovery-window alarms are counted as false positives without label dilation or point adjustment.",
                               "The generator has strong, simple fault injections; independent seeds do not establish new-device generalization."]}
    write_json(output / "benchmark.json", summary)
    return summary


def main():
    parser = argparse.ArgumentParser(description="冻结选型后，在多个全新测试集配对比较原模型与优化模型")
    parser.add_argument("--development-manifest", type=Path, required=True)
    parser.add_argument("--test-manifests", type=Path, nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run_benchmark(args.development_manifest, args.test_manifests, args.output)
    except (ValueError, OSError, KeyError) as error:
        parser.exit(2, f"比较失败：{error}\n")
    print(json.dumps({"selected": result["selection"]["selected_candidate"], "overall": result["overall"]}, indent=2))


if __name__ == "__main__":
    main()
