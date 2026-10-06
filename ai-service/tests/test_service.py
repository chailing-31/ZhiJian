"""Contract tests only. Tiny synthetic images and fake engines do not measure model accuracy."""
from io import BytesIO
import json
from types import SimpleNamespace
import hashlib
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from inspection_service.config import Settings
from inspection_service.engine import YoloEngine, validate_detections
from inspection_service.main import create_app
from inspection_service.burden import calculate_defect_burden
from inspection_service.media import decode_image, InvalidImage
from inspection_service.schemas import Detection, ModelManifest


def image_bytes(fmt='PNG', size=(40, 30), mode='RGB'):
    data = BytesIO()
    Image.new(mode, size, 0).save(data, format=fmt)
    return data.getvalue()


def manifest(**kwargs):
    data = dict(task='apple_surface_defect_detection', model_version='CONTRACT-TEST-ONLY',
        weights='best.pt', weights_sha256='0' * 64,
        classes=[{'id': 0, 'name': 'spot', 'label': '测试类别'}],
        training_data_source='SYNTHETIC-TEST-NOT-A-TRAINED-MODEL')
    data.update(kwargs)
    return ModelManifest(**data)


class FakeEngine:
    """Injected only via tests; there is no app setting that enables this backend."""
    def __init__(self, results=None, fail=False):
        self.ready = True
        self.error_code = None
        self.manifest = manifest()
        self.results = results if results is not None else []
        self.fail = fail

    def initialize(self):
        pass

    def predict(self, image):
        if self.fail:
            raise RuntimeError('private internal exception')
        return self.results


@pytest.fixture
def cfg(tmp_path):
    return Settings(storage_root=tmp_path / 'private-predictions')


def post(client, data=None, batch_id='1', batch_code='APPLE-2026-001'):
    form = {'batch_id': batch_id}
    if batch_code is not None:
        form['batch_code'] = batch_code
    return client.post('/ai/inspection/predict', data=form,
        files={'image': ('../../arbitrary.png', data if data is not None else image_bytes(), 'image/png')})


def test_no_model_liveness_is_not_readiness(cfg):
    with TestClient(create_app(cfg)) as c:
        assert c.get('/health').json()['status'] == 'ok'
        assert c.get('/health').json()['model_ready'] is False
        assert c.get('/ready').status_code == 503
        assert post(c).json()['detail']['code'] == 'MODEL_NOT_READY'
        assert post(c).status_code == 503
        assert not cfg.storage_root.exists()


def test_contract_and_internal_artifacts(cfg):
    d = Detection(class_id=0, class_name='spot', class_label='测试类别',
                  confidence=0.9, bbox_xyxy=(1, 2, 14, 18))
    with TestClient(create_app(cfg, FakeEngine([d]))) as c:
        response = post(c)
        assert response.status_code == 200, response.text
        item = response.json()
        assert item['batch_id'] == 1
        assert item['batch_code'] == 'APPLE-2026-001'
        assert item['suggested_grade'] is None
        assert item['requires_human_review'] is True
        assert item['observation'] == 'target_defect_detected'
        assert item['detections'][0]['bbox_xyxy'] == [1, 2, 14, 18]
        burden = item['defect_burden']
        assert burden['rule_version'] == 'a11-dev-burden-v1'
        assert burden['burden_level'] == 'high'
        assert burden['detection_count'] == 1
        assert burden['union_bbox_area_ratio_image'] == pytest.approx((13 * 16) / (40 * 30))
        assert burden['max_bbox_area_ratio_image'] == pytest.approx((13 * 16) / (40 * 30))
        assert burden['denominator'] == 'full_image_area'
        assert burden['class_summary'][0]['count'] == 1
        assert item['image']['width'] == 40 and item['image']['height'] == 30
        assert item['image']['source_sha256'] == hashlib.sha256(image_bytes()).hexdigest()
        assert 'CONTRACT-TEST-ONLY' == item['model_version']
        assert item['evaluation_status'] == 'not_evaluated'
        assert 'password' not in json.dumps(item)
        for variant, url in item['artifacts'].items():
            result = c.get(url)
            assert result.status_code == 200
            assert result.headers['cache-control'] == 'no-store'
            if variant == 'record':
                assert result.json() == item
        assert c.get(item['artifacts']['source']).content == image_bytes()
        assert (cfg.storage_root / item['prediction_id'] / 'record.json').is_file()
        assert len(list(cfg.storage_root.iterdir())) == 1


def test_empty_result_is_not_normal_or_grade_a(cfg):
    with TestClient(create_app(cfg, FakeEngine())) as c:
        item = post(c).json()
        assert item['observation'] == 'no_target_defect_detected'
        assert item['grade_status'] == 'grading_rule_not_configured'
        assert item['requires_human_review'] is True
        assert item['suggested_grade'] is None
        assert item['detections'] == []
        assert item['defect_burden'] == {
            'rule_version': 'a11-dev-burden-v1',
            'burden_level': 'none_observed',
            'detection_count': 0,
            'union_bbox_area_ratio_image': 0.0,
            'max_bbox_area_ratio_image': 0.0,
            'escalation_flags': [],
            'denominator': 'full_image_area',
            'class_summary': [],
        }




def _a11_detection(class_id, class_name, box):
    return Detection(class_id=class_id, class_name=class_name, class_label=class_name,
                     confidence=0.9, bbox_xyxy=box)


def test_a11_burden_thresholds_and_escalation():
    low = calculate_defect_burden([_a11_detection(9, 'other', (0, 0, 50, 100))], 1000, 1000)
    assert low.burden_level == 'low'

    moderate = calculate_defect_burden([_a11_detection(9, 'other', (0, 0, 60, 100))], 1000, 1000)
    assert moderate.burden_level == 'moderate'

    high = calculate_defect_burden([_a11_detection(9, 'other', (0, 0, 300, 100))], 1000, 1000)
    assert high.burden_level == 'high'

    four = calculate_defect_burden([
        _a11_detection(9, 'other', (0, 0, 20, 20)),
        _a11_detection(9, 'other', (30, 0, 50, 20)),
        _a11_detection(9, 'other', (60, 0, 80, 20)),
        _a11_detection(9, 'other', (90, 0, 110, 20)),
    ], 1000, 1000)
    assert four.burden_level == 'moderate'
    assert four.escalation_flags == ['detection_count_ge_4']

    scratch = calculate_defect_burden(
        [_a11_detection(0, 'ssda_class_0', (0, 0, 250, 100))], 1000, 1000
    )
    assert scratch.burden_level == 'high'
    assert scratch.escalation_flags == ['scratch_large_box_ge_0_025']

    pest = calculate_defect_burden(
        [_a11_detection(1, 'ssda_class_1', (0, 0, 45, 100))], 1000, 1000
    )
    assert pest.burden_level == 'moderate'
    assert pest.escalation_flags == ['pest_damage_large_box_ge_0_0045']


@pytest.mark.parametrize('batch_id', ['0', '-1', 'APPLE-2026-001', '1.5'])
def test_invalid_batch_id(cfg, batch_id):
    with TestClient(create_app(cfg)) as c:
        assert post(c, batch_id=batch_id).status_code == 422


@pytest.mark.parametrize('batch_code', ['a/b', '汉字', 'a' * 65])
def test_invalid_batch_code(cfg, batch_code):
    with TestClient(create_app(cfg)) as c:
        assert post(c, batch_code=batch_code).status_code == 422


def test_omitted_batch_code(cfg):
    with TestClient(create_app(cfg, FakeEngine())) as c:
        r = post(c, batch_code=None)
        assert r.status_code == 200
        assert r.json()['batch_code'] is None


@pytest.mark.parametrize('data', [b'', b'not an image', b'\x89PNG\r\n\x1a\nBAD'])
def test_invalid_image(cfg, data):
    with TestClient(create_app(cfg)) as c:
        assert post(c, data=data).status_code == 422
        assert not cfg.storage_root.exists()


def test_file_byte_limit(tmp_path):
    settings = Settings(storage_root=tmp_path/'store', max_upload_bytes=10)
    with TestClient(create_app(settings)) as c:
        assert post(c).status_code == 413
        assert post(c).json()['detail']['code'] == 'IMAGE_TOO_LARGE'


def test_request_byte_limit(tmp_path):
    settings = Settings(storage_root=tmp_path/'store', max_request_bytes=40)
    with TestClient(create_app(settings)) as c:
        assert post(c).status_code == 413
        assert post(c).json()['detail']['code'] == 'REQUEST_TOO_LARGE'
        r = c.post('/ai/inspection/predict', content=iter([b'a'*30, b'b'*30]))
        assert r.status_code == 413  # Chunked input without Content-Length.


def test_inference_failure_does_not_save(cfg):
    with TestClient(create_app(cfg, FakeEngine(fail=True))) as c:
        r = post(c)
        assert r.status_code == 503
        assert 'private internal exception' not in r.text
        assert r.json()['detail']['code'] == 'INFERENCE_FAILED'
        assert not cfg.storage_root.exists()


def test_invalid_model_box_rejected(cfg):
    d = Detection(class_id=0, class_name='spot', class_label='test',
                  confidence=0.8, bbox_xyxy=(-1, 0, 10, 10))
    with TestClient(create_app(cfg, FakeEngine([d]))) as c:
        assert post(c).status_code == 503
        assert not cfg.storage_root.exists()


def test_storage_failure_returns_error(cfg):
    cfg.storage_root.write_text('This is a file, not a directory.')
    with TestClient(create_app(cfg, FakeEngine())) as c:
        r = post(c)
        assert r.status_code == 507
        assert r.json()['detail']['code'] == 'ARTIFACT_STORAGE_FAILED'


def test_unknown_artifacts(cfg):
    with TestClient(create_app(cfg)) as c:
        assert c.get('/internal/artifacts/' + str(uuid4()) + '/source').status_code == 404
        assert c.get('/internal/artifacts/not-a-uuid/source').status_code == 422
        assert c.get('/internal/artifacts/' + str(uuid4()) + '/secret').status_code == 422


@pytest.mark.parametrize('fmt', ['JPEG', 'PNG', 'WEBP'])
def test_supported_image_formats(fmt):
    result, actual = decode_image(image_bytes(fmt), 1_000_000)
    assert result.size == (40, 30) and result.mode == 'RGB'
    assert actual == fmt


def test_exif_orientation_normalized():
    image = Image.new('RGB', (40, 30))
    exif = Image.Exif()
    exif[274] = 6
    data = BytesIO()
    image.save(data, format='JPEG', exif=exif)
    result, _ = decode_image(data.getvalue(), 1_000_000)
    assert result.size == (30, 40)


def test_alpha_normalization():
    result, _ = decode_image(image_bytes(mode='RGBA'), 1_000_000)
    assert result.getpixel((0, 0)) == (255, 255, 255)


def test_oversized_dimensions():
    with pytest.raises(InvalidImage, match='IMAGE_DIMENSIONS_TOO_LARGE'):
        decode_image(image_bytes(size=(11, 10)), 100)


def test_unsupported_format():
    with pytest.raises(InvalidImage, match='IMAGE_FORMAT_UNSUPPORTED'):
        decode_image(image_bytes('BMP'), 1_000_000)


def test_animated_image():
    data = BytesIO()
    Image.new('RGB', (20, 20), 'red').save(data, format='PNG', save_all=True,
        append_images=[Image.new('RGB', (20, 20), 'blue')], duration=100, loop=0)
    with pytest.raises(InvalidImage, match='ANIMATED_IMAGE_UNSUPPORTED'):
        decode_image(data.getvalue(), 1_000_000)


@pytest.mark.parametrize('box', [(0, 0, 0, 4), (3, 2, 1, 4), (0, 0, 41, 29), (float('nan'), 0, 1, 2)])
def test_box_validation(box):
    d = Detection(class_id=0, class_name='spot', class_label='test', confidence=0.5, bbox_xyxy=box)
    with pytest.raises(ValueError):
        validate_detections([d], 40, 30)


def test_manifest_duplicates_rejected():
    with pytest.raises(ValueError):
        manifest(classes=[{'id': 0, 'name': 'a', 'label': 'a'}, {'id': 0, 'name': 'b', 'label': 'b'}])


def test_manifest_evaluated_requires_reference():
    with pytest.raises(ValueError):
        manifest(evaluation_status='evaluated')


def write_manifest(tmp_path, **kwargs):
    path = tmp_path / 'manifest.json'
    path.write_text(manifest(**kwargs).model_dump_json(), encoding='utf-8')
    return path


def test_no_weight_does_not_import_or_download(tmp_path, monkeypatch):
    file = write_manifest(tmp_path)
    def blocked(*args):
        pytest.fail('YOLO must not be imported before checking local weights.')
    monkeypatch.setattr('inspection_service.engine.importlib.import_module', blocked)
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=file))
    e.initialize()
    assert e.ready is False and e.error_code == 'MODEL_WEIGHTS_MISSING'


def test_wrong_hash_rejected(tmp_path, monkeypatch):
    (tmp_path/'best.pt').write_bytes(b'NOT A REAL WEIGHT')
    file = write_manifest(tmp_path)
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=file))
    e.initialize()
    assert e.error_code == 'MODEL_HASH_MISMATCH'


def test_manifest_missing(tmp_path):
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=tmp_path/'missing.json'))
    e.initialize()
    assert e.error_code == 'MODEL_MANIFEST_MISSING'


def test_manifest_invalid(tmp_path):
    f = tmp_path/'bad.json'
    f.write_text('{}')
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=f))
    e.initialize()
    assert e.error_code == 'MODEL_MANIFEST_INVALID'


def test_model_classes_rejected(tmp_path, monkeypatch):
    data = b'NOT A REAL WEIGHT'
    (tmp_path/'best.pt').write_bytes(data)
    file = write_manifest(tmp_path, weights_sha256=hashlib.sha256(data).hexdigest())
    fake = SimpleNamespace(task='detect', names={0:'apple'})
    monkeypatch.setattr('inspection_service.engine.importlib.import_module', lambda _: SimpleNamespace(YOLO=lambda _:fake))
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=file))
    e.initialize()
    assert e.error_code == 'MODEL_CLASSES_MISMATCH'


def test_wrong_task_rejected(tmp_path, monkeypatch):
    data = b'NOT A REAL WEIGHT'
    (tmp_path/'best.pt').write_bytes(data)
    file = write_manifest(tmp_path, weights_sha256=hashlib.sha256(data).hexdigest())
    fake = SimpleNamespace(task='classify', names={0:'spot'})
    monkeypatch.setattr('inspection_service.engine.importlib.import_module', lambda _: SimpleNamespace(YOLO=lambda _:fake))
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=file))
    e.initialize()
    assert e.error_code == 'MODEL_TASK_MISMATCH'


def test_model_adapter_contract(tmp_path, monkeypatch):
    data = b'NOT A REAL WEIGHT'
    (tmp_path/'best.pt').write_bytes(data)
    file = write_manifest(tmp_path, weights_sha256=hashlib.sha256(data).hexdigest())
    class TensorLike:
        def __init__(self, values): self.values = values
        def cpu(self): return self
        def tolist(self): return self.values
    boxes = SimpleNamespace(xyxy=TensorLike([[1,2,5,6]]),conf=TensorLike([0.7]),cls=TensorLike([0.0]))
    fake = SimpleNamespace(task='detect', names={0:'spot'},
        predict=lambda **kwargs: [SimpleNamespace(orig_shape=(30,40), boxes=boxes)])
    monkeypatch.setattr('inspection_service.engine.importlib.import_module', lambda _: SimpleNamespace(YOLO=lambda _:fake))
    e = YoloEngine(Settings(storage_root=tmp_path/'out', manifest_path=file))
    e.initialize()
    assert e.ready is True
    r = e.predict(Image.new('RGB', (40,30)))
    assert r[0].bbox_xyxy == (1,2,5,6)
    assert r[0].class_label == '测试类别'


def test_settings_require_absolute_storage(monkeypatch):
    monkeypatch.delenv('AI_STORAGE_ROOT', raising=False)
    with pytest.raises(ValueError): Settings.from_env()
    monkeypatch.setenv('AI_STORAGE_ROOT', 'relative')
    with pytest.raises(ValueError): Settings.from_env()


def test_openapi_available_without_weights(cfg):
    with TestClient(create_app(cfg)) as c:
        paths = c.get('/openapi.json').json()['paths']
        assert '/ai/inspection/predict' in paths
        assert '/health' in paths and '/ready' in paths
