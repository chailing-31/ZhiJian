"""Repository-native V4 launcher. No patch installation, Git reset or model promotion.
Use one terminal per service. This launcher is limited to a trusted local Windows demo.
"""
from __future__ import annotations
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import urllib.error
import urllib.request
import warnings

REPO = Path(__file__).resolve().parents[1]
DB = 'smart_fresh_c_review'
VERSION = 'batch-workbench-v4-20261013'
OLD_SHA = '8c45a0602dadf8b9f48b609e29d6d3b5e5868db5f68682da8b32583e104827a4'


def run(args, cwd, env=None, **kwargs):
    return subprocess.run(args, cwd=cwd, env=env, check=True, **kwargs)


def cmd(text, cwd, env=None):
    return run([os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/c', text], cwd, env)


def http(url):
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=15) as response:
            return json.load(response)
    except (OSError, ValueError) as exc:
        raise ValueError(f'Cannot read {url}: {exc}. Keep that service terminal open.') from None


def free(port):
    with socket.socket() as sock:
        sock.settimeout(0.5)
        if sock.connect_ex(('127.0.0.1', port)) == 0:
            raise ValueError(f'Port {port} is occupied. No process was killed.')


def check_source():
    source = REPO / 'backend/src/main/java/com/zhijian/demo/workspace/EngineeringWorkspaceController.java'
    if not source.is_file() or VERSION not in source.read_text(encoding='utf-8-sig'):
        raise ValueError('Run this launcher from the V4 snapshot repository.')


def accepted_model():
    default = REPO / 'ai-service/models/accepted/model-manifest.json'
    if not default.is_file():
        default = Path(r'D:\ZhiJianData\models\ssda-yolov8n-dev-v1\model-manifest.json')
    path = Path(os.environ.get('ZJ_ACCEPTED_MANIFEST', str(default)))
    if not path.is_file() or path.stat().st_size > 65536:
        raise ValueError('Set ZJ_ACCEPTED_MANIFEST to the trusted OLD model manifest. Weight is not in this snapshot.')
    manifest = json.loads(path.read_text(encoding='utf-8-sig'))
    weight = Path(manifest['weights'])
    if not weight.is_absolute():
        weight = path.parent / weight
    if not weight.is_file() or manifest.get('weights_sha256') != OLD_SHA:
        raise ValueError('The manifest does not identify the selected old weight. No A15 fallback.')
    digest = hashlib.sha256()
    with weight.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != OLD_SHA:
        raise ValueError('OLD model SHA256 mismatch.')
    if tuple(manifest.get(k) for k in ('confidence_threshold', 'iou_threshold', 'image_size')) != (.25, .5, 640):
        raise ValueError('Model settings differ from the selected baseline. Nothing rewritten.')
    return path.resolve(), manifest


def interpreter(kind):
    candidates = ([os.environ.get('AI_PYTHON'), str(REPO / 'ai-service/.venv/Scripts/python.exe'),
                   r'D:\ZhiJianData\envs\ai-cpu\Scripts\python.exe'] if kind == 'ai'
                  else [os.environ.get('B_PYTHON'), str(REPO / 'business/.venv/Scripts/python.exe')])
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise ValueError(f'Set {"AI_PYTHON" if kind == "ai" else "B_PYTHON"} to the existing environment python.exe. No dependencies installed.')


def backend_env(user, password):
    env = os.environ.copy()
    for key in ('SPRING_APPLICATION_JSON', 'SPRING_CONFIG_LOCATION', 'SPRING_CONFIG_ADDITIONAL_LOCATION'):
        if env.get(key):
            raise ValueError(f'{key} is set. Use a clean CMD to preserve the isolated database target.')
    storage = Path(os.environ.get('ZJ_REVIEW_DATA', r'D:\ZhiJianData\c-review')) / 'backend-inspection-artifacts'
    env.update(DB_HOST='127.0.0.1', DB_PORT='3306', DB_NAME=DB, DB_USER=user, DB_PASSWORD=password,
        SPRING_DATASOURCE_URL=f'jdbc:mysql://127.0.0.1:3306/{DB}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai',
        SPRING_DATASOURCE_USERNAME=user, SPRING_DATASOURCE_PASSWORD=password,
        AI_SERVICE_BASE_URL='http://127.0.0.1:8001', BUSINESS_SERVICE_BASE_URL='http://127.0.0.1:8002',
        INSPECTION_STORAGE_ROOT=str(storage), SERVER_PORT='8080', SERVER_ADDRESS='127.0.0.1')
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['ai', 'business', 'backend', 'web', 'status', 'migrate'])
    args = parser.parse_args()
    if os.name != 'nt':
        raise ValueError('Service launcher targets Windows. See docs/V4_GITHUB_HANDOFF.md for environment setup.')
    check_source()
    action = args.action
    if action == 'status':
        failed = False
        for name, url in [('A', 'http://127.0.0.1:8001/ready'), ('B', 'http://127.0.0.1:8002/health'),
                          ('V4', 'http://127.0.0.1:5173/api/workspace/status')]:
            try:
                result = http(url)
                print(name, json.dumps(result, ensure_ascii=True))
                if name == 'A':
                    good = result.get('model_ready') is True and result.get('model_version') == 'ssda-yolov8n-20261001-8c45a0602dad-n050-sem1'
                elif name == 'B':
                    good = result.get('service') == 'business-rules' and result.get('version') == '0.5.0'
                else:
                    good = result.get('patch_version') == VERSION and result.get('batch_workbench_ready') is True and result.get('database_name') == DB
                failed = failed or not good
            except ValueError as exc:
                failed = True
                print('FAILED:', exc)
        if failed:
            raise ValueError('One or more service checks failed. No certification inferred.')
        print('V4_SERVICES_READY. Full write-flow acceptance remains separate.')
    elif action == 'ai':
        free(8001)
        path, manifest = accepted_model()
        env = os.environ.copy()
        env.update(AI_PYTHON=interpreter('ai'), AI_MODEL_MANIFEST=str(path), AI_INFERENCE_PROFILE='baseline')
        print('Selected OLD model:', manifest['model_version'])
        cmd('run-cpu-local.cmd', REPO / 'ai-service', env)
    elif action == 'business':
        free(8002)
        run([interpreter('business'), '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', '8002'], REPO / 'business')
    elif action == 'backend':
        free(8080)
        _, manifest = accepted_model()
        a = http('http://127.0.0.1:8001/ready')
        b = http('http://127.0.0.1:8002/health')
        if a.get('model_ready') is not True or a.get('model_version') != manifest['model_version']:
            raise ValueError('A is not serving the selected old model.')
        if b.get('service') != 'business-rules' or b.get('version') != '0.5.0':
            raise ValueError('B 0.5.0 is required on 8002.')
        print('Database:', DB, '(must already exist with base/A3/C/Q4 migrations)')
        user = input('MySQL username (Enter=root): ').strip() or 'root'
        with warnings.catch_warnings():
            warnings.simplefilter('error', getpass.GetPassWarning)
            password = getpass.getpass('MySQL password (hidden): ')
        if not password:
            raise ValueError('Empty password not accepted by this local launcher.')
        cmd('run-inspection.cmd', REPO / 'backend', backend_env(user, password))
    elif action == 'web':
        free(5173)
        if not (REPO / 'frontend/node_modules/.bin/vite.cmd').is_file():
            raise ValueError('Install pinned dependencies in frontend with npm ci --include=dev first.')
        cmd('npm run dev -- --port 5173 --strictPort --open', REPO / 'frontend')
    elif action == 'migrate':
        free(8080)
        exe = shutil.which('mysql.exe') or shutil.which('mysql')
        if not exe:
            raise ValueError('mysql.exe must be on PATH.')
        print('Only adds q4 tables to existing', DB, '; no base database initialization.')
        user = input('MySQL username (Enter=root): ').strip() or 'root'
        if input('Type ADD_Q4_TABLES to run additive SQL; Enter cancels: ').strip() != 'ADD_Q4_TABLES':
            print('Cancelled.'); return
        env = os.environ.copy(); env.pop('MYSQL_PWD', None)
        sql = (REPO / 'database/migrations/20261013_Q4_batch_workbench.sql').read_bytes()
        run([exe, '--no-defaults', '--host=127.0.0.1', '--port=3306', f'--user={user}', '--password',
             f'--database={DB}', '--default-character-set=utf8mb4'], REPO, env,
             input=b'SELECT DATABASE();\n' + sql + b'\nSHOW TABLES LIKE "q4_%";\n')
        print('Q4_TABLES_ADDED. Start backend and check readiness.')


if __name__ == '__main__':
    try:
        main()
    except KeyboardInterrupt:
        print('Stopped by operator.'); raise SystemExit(130)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError, getpass.GetPassWarning) as exc:
        print('STOP:', exc); raise SystemExit(2)
