"""Frozen model-asset checks; no torch, no model deserialization, no network."""
from __future__ import annotations
import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("verify_a15_asset_tests", ROOT / "ai-service/tools/verify_a15.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class A15AssetsTest(unittest.TestCase):
    def setUp(self):
        self.cfg = json.loads((ROOT / verify.MANIFEST_REL).read_text(encoding="utf-8-sig"))

    def test_real_frozen_weight(self):
        result = verify.verify_bundle(ROOT)
        self.assertEqual(result["bytes"], 6252266)
        self.assertEqual(result["weights_sha256"], verify.EXPECTED_SHA256)

    def test_model_card_is_relative_and_present(self):
        ref = self.cfg["evaluation_report"]
        self.assertNotIn(":", ref)
        self.assertTrue(((ROOT / verify.MANIFEST_REL).parent / ref).resolve().is_file())

    def test_rejects_wrong_hash(self):
        self.cfg["weights_sha256"] = "0" * 64
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_wrong_model_version(self):
        self.cfg["model_version"] = "A15.1"
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_changed_threshold(self):
        self.cfg["confidence_threshold"] = 0.1
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_changed_nms(self):
        self.cfg["iou_threshold"] = 0.7
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_swapped_classes(self):
        self.cfg["classes"].reverse()
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_absolute_weight_path(self):
        self.cfg["weights"] = "D:/somebody/best.pt"
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_false_formal_evaluation_claim(self):
        self.cfg["evaluation_status"] = "evaluated"
        with self.assertRaises(ValueError): verify.validate_config(self.cfg)

    def test_rejects_missing_weight(self):
        with tempfile.TemporaryDirectory() as td:
            with self.assertRaises(ValueError): verify.verify_bundle(Path(td))

    def test_rejects_placeholder_or_lfs_pointer(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td)
            model_dir = root / "ai-service/models/a15"
            model_dir.mkdir(parents=True)
            (model_dir / "model-manifest.json").write_text(json.dumps(self.cfg), encoding="utf-8")
            (model_dir / "best.pt").write_text("version https://git-lfs.github.com/spec/v1\n", encoding="utf-8")
            with self.assertRaises(ValueError): verify.verify_bundle(root)


if __name__ == "__main__":
    unittest.main()
