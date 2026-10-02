"""Read-only liveness/readiness check; NOT a real image inference accuracy test."""
import argparse
import json
import sys
import urllib.error
import urllib.request

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base-url', default='http://127.0.0.1:8001')
    parser.add_argument('--expect', choices=['not-ready', 'ready'], default='not-ready')
    args = parser.parse_args()
    # Direct local access: do not inherit workstation HTTP proxy environment variables.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    def get(path):
        try:
            with opener.open(args.base_url.rstrip('/') + path, timeout=10) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)
    status, state = get('/health')
    if status != 200 or state.get('status') != 'ok':
        raise RuntimeError('Liveness check failed')
    status, ready = get('/ready')
    expected_ready = args.expect == 'ready'
    if status != (200 if expected_ready else 503) or ready.get('model_ready') is not expected_ready:
        raise RuntimeError('Unexpected model readiness: ' + json.dumps(ready, ensure_ascii=False))
    print(json.dumps(state, ensure_ascii=False, indent=2))
    print('Service contract passed. Real apple inference and quality evaluation are NOT validated by this check.')

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
