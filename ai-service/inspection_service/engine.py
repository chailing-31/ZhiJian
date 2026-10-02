"""Only operator-supplied, hash-checked local YOLO detection weights are supported.
The model adapter is not a trained apple model; installing this service does not train one.
"""
import hashlib
import importlib
import math
from pathlib import Path
import threading
from .schemas import Detection, ModelManifest

class ModelUnavailable(RuntimeError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)

def sha256_file(path: Path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

class YoloEngine:
    def __init__(self, settings):
        self.settings = settings
        self.model = None
        self.manifest = None
        self.error_code = 'MODEL_NOT_CONFIGURED'
        self._lock = threading.Lock()

    @property
    def ready(self):
        return self.model is not None and self.error_code is None

    def initialize(self):
        self.model = None
        self.manifest = None
        self.error_code = 'MODEL_NOT_CONFIGURED'
        file = self.settings.manifest_path
        if file is None:
            return
        try:
            if not file.is_file():
                raise ModelUnavailable('MODEL_MANIFEST_MISSING')
            if file.stat().st_size > 64 * 1024:
                raise ModelUnavailable('MODEL_MANIFEST_INVALID')
            try:
                manifest = ModelManifest.model_validate_json(file.read_text(encoding='utf-8-sig'))
            except (ValueError, OSError):
                raise ModelUnavailable('MODEL_MANIFEST_INVALID') from None
            weight = Path(manifest.weights).expanduser()
            if not weight.is_absolute():
                weight = file.parent / weight
            weight = weight.resolve()
            # Check before importing YOLO: a basename must never trigger an automatic download.
            if not weight.is_file():
                raise ModelUnavailable('MODEL_WEIGHTS_MISSING')
            if weight.suffix.lower() != '.pt':
                raise ModelUnavailable('MODEL_FORMAT_UNSUPPORTED')
            if sha256_file(weight) != manifest.weights_sha256:
                raise ModelUnavailable('MODEL_HASH_MISMATCH')
            try:
                YOLO = importlib.import_module('ultralytics').YOLO
            except ImportError:
                raise ModelUnavailable('MODEL_DEPENDENCY_MISSING') from None
            model = YOLO(str(weight))
            if model.task != 'detect':
                raise ModelUnavailable('MODEL_TASK_MISMATCH')
            names = model.names
            if isinstance(names, list):
                names = dict(enumerate(names))
            expected = {c.id: c.name for c in manifest.classes}
            if {int(k): v for k, v in names.items()} != expected:
                raise ModelUnavailable('MODEL_CLASSES_MISMATCH')
            self.model, self.manifest = model, manifest
            self.error_code = None
        except ModelUnavailable as error:
            self.error_code = error.code
        except Exception:
            # Do not include arbitrary library errors, paths, or secrets in API responses.
            self.error_code = 'MODEL_LOAD_FAILED'

    def predict(self, image):
        if not self.ready:
            raise ModelUnavailable(self.error_code)
        with self._lock:
            results = self.model.predict(source=image, device=self.settings.device,
                conf=self.manifest.confidence_threshold, iou=self.manifest.iou_threshold,
                imgsz=self.manifest.image_size, save=False, verbose=False, stream=False)
        if len(results) != 1 or results[0].boxes is None:
            raise RuntimeError('Invalid detection output')
        result = results[0]
        if tuple(result.orig_shape) != (image.height, image.width):
            raise RuntimeError('Image dimensions do not match the result')
        coords = result.boxes.xyxy.cpu().tolist()
        confs = result.boxes.conf.cpu().tolist()
        ids = result.boxes.cls.cpu().tolist()
        definitions = {c.id: c for c in self.manifest.classes}
        output = []
        for box, score, raw_id in zip(coords, confs, ids, strict=True):
            if not math.isfinite(raw_id) or raw_id != int(raw_id):
                raise RuntimeError('Invalid model class ID')
            class_id = int(raw_id)
            if class_id not in definitions:
                raise RuntimeError('Unknown model class')
            c = definitions[class_id]
            output.append(Detection(class_id=class_id, class_name=c.name,
                class_label=c.label, confidence=score, bbox_xyxy=tuple(box)))
        validate_detections(output, image.width, image.height)
        return output

def validate_detections(detections, width, height):
    for detection in detections:
        x1, y1, x2, y2 = detection.bbox_xyxy
        if not all(math.isfinite(v) for v in (x1, y1, x2, y2)):
            raise ValueError('Non-finite bounding box')
        if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
            raise ValueError('Bounding box outside image or empty')
