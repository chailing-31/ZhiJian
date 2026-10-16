"""Real HTTP acceptance; fixture writes require an explicitly isolated test database.
No model accuracy measurements. Test operations and simulation readings are fixtures.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import uuid
import urllib.request
import urllib.error

VERSION = 'ai-first-application-v5.1-20261014'

class Api:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def call(self, path, body=None, expected=200, data=None, headers=None):
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode()
            headers = {'Content-Type': 'application/json', **(headers or {})}
        req = urllib.request.Request(self.base + path, data=data, headers=headers or {})
        try:
            with self.opener.open(req, timeout=90) as response:
                status, raw = response.status, response.read()
        except urllib.error.HTTPError as e:
            status, raw = e.code, e.read()
        if status != expected:
            raise AssertionError(f'{path}: expected {expected}, got {status}: {raw[:700].decode(errors="replace")}')
        return json.loads(raw) if raw else None
    def upload(self, batch, image):
        boundary = 'ZJ' + uuid.uuid4().hex
        raw = image.read_bytes()
        mime = {'.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.webp': 'image/webp'}[image.suffix.lower()]
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="fixture{image.suffix}"\r\nContent-Type: {mime}\r\n\r\n'.encode() + raw + f'\r\n--{boundary}--\r\n'.encode())
        return self.call(f'/batches/{batch}/inspections', data=data, headers={'Content-Type': f'multipart/form-data; boundary={boundary}', 'Idempotency-Key': str(uuid.uuid4())})


def run(base, image=None, fixture_database=None, allow_fixture_writes=False):
    api = Api(base)
    state = api.call('/workspace/status')
    assert state['patch_version'] == VERSION, state
    assert state['batch_workbench_ready'] is True, state
    assert api.call('/inspection-service/ready')['ready'] is True
    assert api.call('/business-service/ready')['ready'] is True
    if not allow_fixture_writes:
        return {'version': VERSION, 'readiness': 'passed', 'writes': False}
    actual = str(state.get('database_name', '')).lower()
    if not fixture_database or fixture_database.lower() != actual or not any(k in actual for k in ('test', 'acceptance')):
        raise ValueError('Fixture writes require a matching isolated database name containing test or acceptance. No write performed.')
    if not image or not Path(image).is_file():
        raise ValueError('Provide a fixture image; no write performed.')
    code = 'TEST-V5-' + uuid.uuid4().hex[:12]
    created = api.call('/batches', {'batch_code': code, 'product': '验收测试夹具', 'variety': '非真实生产批次', 'origin': '隔离验收环境', 'supplier': 'INTEGRATION FIXTURE'})
    bid = created['batch_id']; prefix = f'/batches/{bid}'
    def w(): return api.call(prefix + '/batch-workbench')
    def intent(body): return {'request_id': str(uuid.uuid4()), **body}
    saved = api.upload(bid, Path(image))
    photo_grade = saved['initial_assessment']['grade']
    assert photo_grade in ('A', 'B', 'C', 'REJECT')
    original = saved['prediction']
    current = w()
    assert current['assessment_current'] and current['assessment']['source'] == 'ai_initial'
    assert current['assessment']['final_grade'] == photo_grade
    assert current['evidence']['pending_count'] == 0 and current['evidence']['reviewed_count'] == 0
    aid = current['assessment']['id']
    api.call(prefix + '/auto-assessment', data=b'')
    assert w()['assessment']['id'] == aid
    def advice():
        a = w()['assessment']
        return api.call(prefix + '/assessment-processing-advice', intent({'assessment_id': a['id'], 'assessment_revision': a['revision'], 'operator': '验收夹具', 'use_as_process_input_confirmed': True, 'temperature': 5, 'humidity': 80, 'material_temperature': 20}))
    initial_advice = advice()
    assert initial_advice['workbench']['processing']['inputs']['grade'] == photo_grade
    # Check the standard goods path when the real fixture inference yields A/B.
    now = datetime.now(timezone.utc).replace(microsecond=0)
    def operation(kind):
        cur = w(); a = cur['assessment']; p = cur['processing']
        body = {'assessment_id': a['id'], 'assessment_revision': a['revision'], 'operator': '验收夹具', 'kind': kind, 'note': 'INTEGRATION FIXTURE ONLY，无真实生产作业', 'performed_at': (now - timedelta(minutes=20)).isoformat(), 'actually_performed_confirmed': True}
        if kind == 'processing_completed': body.update(processing_id=p['record_id'], expected_processing_fingerprint=p['fingerprint'])
        return api.call(prefix + '/batch-operations', intent(body))
    def adopt(expected=200):
        p = w()['processing']
        return api.call(f'/processing-records/{p["record_id"]}/adopt', {'operator': '验收夹具', 'note': 'INTEGRATION FIXTURE ONLY，验证采用版本', 'adopted_values': {'pre_cooling_time': '6h', 'washing_pressure': 'normal', 'action': 'FIXTURE ONLY'}, 'expected_processing_fingerprint': p['fingerprint']}, expected=expected)
    if photo_grade in ('A', 'B'):
        operation('sorting_completed'); adopt(); operation('processing_completed')
        assert w()['processing_executed']
    else:
        operation('quality_disposition')
        assert w()['quality_disposition_recorded'] and not w()['processing_executed']
    reading_start = now - timedelta(minutes=12)
    for minute in range(7):
        api.call(prefix + '/sensor-readings', {'timestamp': (reading_start + timedelta(minutes=minute)).isoformat(), 'temperature': 12, 'humidity': 80, 'source': 'simulation'})
    cur = w(); assert cur['coldchain']['hold'] is True and cur['coldchain']['unresolved_alerts'] > 0
    if photo_grade in ('A', 'B'): adopt(expected=409)
    api.call(prefix + '/sensor-readings', {'timestamp': (reading_start + timedelta(minutes=7)).isoformat(), 'temperature': 4, 'humidity': 80, 'source': 'simulation'})
    assert w()['coldchain']['hold'] is True
    cold = api.call(prefix + '/coldchain')
    for alert in cold['alerts']:
        if alert['status'] != 'resolved': api.call(f'/alerts/{alert["alert_id"]}/resolve', {'resolved_by': '验收夹具', 'resolution': 'INTEGRATION FIXTURE ONLY，恢复与处置测试'})
    assert w()['coldchain']['hold'] is False
    opts = {'publish_grade': True, 'publish_use': True}
    preview = api.call(prefix + '/batch-summary/preview', opts)
    api.call(prefix + '/batch-summary/publish', intent({'expected_fingerprint': preview['source_fingerprint'], **opts, 'operator': '验收夹具', 'confirm_public_summary': True}))
    public = api.call(f'/trace/{code}/batch-summary')
    assert public['published'] and public['current'] and public['snapshot']['grade_source'] == 'AI 初评'
    for secret in ('review_remark', 'approved_by', 'candidate_reviews', 'image_hash', 'INTEGRATION FIXTURE'):
        assert secret not in json.dumps(public['snapshot'], ensure_ascii=False)
    internal = api.call(prefix + '/report')
    assert internal['batch_workbench']['assessment']['id'] == w()['assessment']['id']
    assert internal['coldchain']['summary']['reading_count'] == 8
    # Optional human correction creates a new source version and stales downstream records.
    cur = w(); a = cur['assessment']
    corrected = api.call(prefix + '/batch-assessments', intent({'round_id': cur['round']['id'], 'expected_fingerprint': cur['evidence']['fingerprint'], 'expected_assessment_id': a['id'], 'final_grade': 'REJECT', 'intended_use': 'isolate_return', 'grade_basis': '验收夹具：验证人工修正与版本联动，无真实品质结论', 'operator': '验收夹具', 'scope_confirmed': True, 'use_confirmed': True}))['workbench']
    assert corrected['assessment']['source'] == 'human_corrected' and not corrected['processing']['current']
    assert api.call(f'/trace/{code}/batch-summary')['current'] is False
    # AI output stays immutable, and refresh cannot undo a current human correction.
    assert api.call(f'/inspections/{saved["inspection_id"]}')['prediction'] == original
    api.call(prefix + '/auto-assessment', data=b'')
    assert w()['assessment']['id'] == corrected['assessment']['id']
    a = w()['assessment']
    hold = api.call(prefix + '/assessment-processing-advice', intent({'assessment_id': a['id'], 'assessment_revision': a['revision'], 'operator': '验收夹具', 'use_as_process_input_confirmed': True}))['workbench']
    assert hold['processing']['advice_status'] == 'blocked' and hold['processing']['inputs']['grade'] == 'REJECT'
    operation('quality_disposition')
    assert w()['state'] == 'DISPOSITION_RECORDED'
    return {'version': VERSION, 'database': actual, 'batch_id': bid, 'batch_code': code, 'real_model_version': original['model_version'], 'fixture_initial_grade': photo_grade, 'checks': ['upload_auto_grade_without_human', 'initial_advice_from_same_source', 'idempotent_auto_sync', 'actual_operation_fixture', 'simulation_alert_recovery_resolution', 'coldchain_blocks_adoption', 'internal_report', 'whitelist_publication', 'manual_correction_versions', 'stale_processing_and_publication', 'immutable_model_output', 'REJECT_hold_without_environment', 'quality_disposition'], 'accuracy_evaluation': False, 'real_production_operations': False}

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base', default='http://127.0.0.1:5173/api')
    p.add_argument('--image', type=Path)
    p.add_argument('--fixture-database')
    p.add_argument('--allow-fixture-writes', action='store_true')
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    result = run(args.base, args.image, args.fixture_database, args.allow_fixture_writes)
    if args.output: args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
