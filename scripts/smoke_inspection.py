"""Explicitly writes one inspection (and optionally one review) to a LOCAL development backend.
python scripts/smoke_inspection.py --image D:/path/a.jpg [--write-review]
No mock mode. Uses existing APPLE-2026-001; never creates/deletes batches.
"""
import argparse, hashlib, json, mimetypes, urllib.request, urllib.error, uuid
from pathlib import Path


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--image', type=Path, required=True)
    p.add_argument('--base', default='http://127.0.0.1:8080')
    p.add_argument('--batch-code', default='APPLE-2026-001')
    p.add_argument('--write-review', action='store_true')
    a = p.parse_args()
    from urllib.parse import urlsplit
    url = urlsplit(a.base)
    if url.hostname not in ('localhost', '127.0.0.1', '::1'):
        raise SystemExit('This write test only allows a loopback development backend.')
    image = a.image.read_bytes()
    if not image or len(image) > 10 * 1024 * 1024: raise SystemExit('Image must be 1 byte to 10 MiB.')
    mime = mimetypes.guess_type(a.image.name)[0]
    if mime not in ('image/png', 'image/jpeg', 'image/webp'): raise SystemExit('Use JPG/PNG/WebP.')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def request(path, body=None, method='GET', headers=None, raw=False):
        req = urllib.request.Request(a.base.rstrip('/') + path, data=body, method=method, headers=headers or {})
        with opener.open(req, timeout=300) as r:
            content = r.read()
            return content if raw else json.loads(content)
    state = request('/inspection-service/ready')
    if not state.get('ready'): raise SystemExit('Backend/AI/schema is not ready: ' + json.dumps(state, ensure_ascii=False))
    batches = request('/batches')
    found = [b for b in batches if b['batch_code'] == a.batch_code]
    if len(found) != 1: raise SystemExit('Demo batch not found. Initialize it using the existing SQL, then retry.')
    batch_id = found[0]['batch_id']
    boundary = 'ZhiJianSmoke' + uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="upload"\r\nContent-Type: {mime}\r\n\r\n'.encode() + image + f'\r\n--{boundary}--\r\n'.encode())
    headers = {'Content-Type': 'multipart/form-data; boundary=' + boundary, 'Idempotency-Key': str(uuid.uuid4())}
    first = request(f'/batches/{batch_id}/inspections', body, 'POST', headers)
    second = request(f'/batches/{batch_id}/inspections', body, 'POST', headers)
    if first['inspection_id'] != second['inspection_id']: raise RuntimeError('Idempotency failed')
    if first['prediction']['image']['source_sha256'] != hashlib.sha256(image).hexdigest(): raise RuntimeError('Source SHA mismatch')
    iid = first['inspection_id']
    for variant in ('input', 'result'):
        b = request(f'/inspections/{iid}/artifacts/{variant}', raw=True)
        if not b.startswith(b'\x89PNG\r\n\x1a\n'): raise RuntimeError('Artifact is not PNG')
    if a.write_review:
        # Mark every candidate uncertain; this is a workflow test, not a fake human confirmation.
        review = {'expected_revision': first['review_revision'], 'reviewer': 'local-smoke-test', 'conclusion': 'needs_recheck', 'remark': '自动接口验收记录，未进行人工判断。', 'publish_summary': False, 'candidate_reviews': [{'candidate_index': i+1, 'decision': 'uncertain', 'duplicate_of': None, 'note': '尚未人工复核'} for i, _ in enumerate(first['prediction']['detections'])]}
        updated = request(f'/inspections/{iid}/review', json.dumps(review,ensure_ascii=False).encode(), 'PATCH', {'Content-Type':'application/json'})
        if updated['prediction'] != first['prediction']: raise RuntimeError('Raw prediction changed after review')
    print('INSPECTION_SMOKE_PASSED: real upload, persistence, idempotency, input/result image retrieval' + (', append review' if a.write_review else ''))
    print('Saved inspection_id=', iid, '; batch_id=', batch_id, '; inference_ms=', first['prediction']['inference_ms'])
    print('This proves API flow only, not model accuracy. Test data was retained in your local database.')

if __name__ == '__main__':
    try: main()
    except urllib.error.HTTPError as e: raise SystemExit(f'HTTP {e.code}: ' + e.read().decode(errors='replace'))
