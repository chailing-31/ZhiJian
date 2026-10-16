"""Application entry point. Existing A/B services may be reused after version checks.
Credentials are entered once in the local console and passed only to Java's environment.
"""
from __future__ import annotations
import getpass
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import warnings
import v4_local as local


def occupied(port):
    with socket.socket() as sock:
        sock.settimeout(.5)
        return sock.connect_ex(('127.0.0.1', port)) == 0


def console(command, cwd, env=None):
    return subprocess.Popen([os.environ.get('COMSPEC', 'cmd.exe'), '/d', '/k', command], cwd=cwd,
                            env=env, creationflags=subprocess.CREATE_NEW_CONSOLE)


def wait_for(url, condition, label, timeout=50):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            value=local.http(url)
            if condition(value):
                print(label, 'ready.');return value
        except ValueError:
            pass
        time.sleep(1)
    raise ValueError(f'{label} did not become ready. Check its open service console; no existing process was stopped.')


def start():
    if os.name!='nt': raise ValueError('The launcher targets Windows. Use the individual documented commands on other systems.')
    local.check_source()
    # A stale Java/frontend process must not serve a different application version.
    local.free(8080);local.free(5173)
    manifest_path,manifest=local.accepted_model()
    ai_good=lambda r:r.get('model_ready') is True and r.get('model_version')==manifest['model_version']
    b_good=lambda r:r.get('service')=='business-rules' and r.get('version')=='0.5.0'
    a_url='http://127.0.0.1:8001/ready';b_url='http://127.0.0.1:8002/health'
    reuse_a=occupied(8001);reuse_b=occupied(8002)
    if reuse_a and not ai_good(local.http(a_url)): raise ValueError('Port 8001 is occupied by a different or unready model. Check that console first.')
    if reuse_b and not b_good(local.http(b_url)): raise ValueError('Port 8002 is occupied by a different or unready business service.')
    if not reuse_a: ai_python=local.interpreter('ai')
    if not reuse_b: b_python=local.interpreter('business')
    if not (local.REPO/'frontend/node_modules/.bin/vite.cmd').is_file():
        raise ValueError('In frontend, run npm ci --include=dev before starting.')
    print('Application:',local.VERSION,'Database:',local.DB)
    user=input('MySQL username (Enter=root): ').strip() or 'root'
    with warnings.catch_warnings():
        warnings.simplefilter('error',getpass.GetPassWarning)
        password=getpass.getpass('MySQL password (hidden, not saved): ')
    if not password: raise ValueError('Enter the existing database password locally.')
    java_env=local.backend_env(user,password)
    if not reuse_a:
        ai_env=os.environ.copy();ai_env.update(AI_PYTHON=ai_python,AI_MODEL_MANIFEST=str(manifest_path),AI_INFERENCE_PROFILE='baseline')
        console('run-cpu-local.cmd',local.REPO/'ai-service',ai_env)
    if not reuse_b:
        b_env=os.environ.copy();b_env['B_PYTHON']=b_python
        console('"%B_PYTHON%" -m uvicorn app.main:app --host 127.0.0.1 --port 8002',local.REPO/'business',b_env)
    wait_for(a_url,ai_good,'AI model');wait_for(b_url,b_good,'Business service')
    console('run-inspection.cmd',local.REPO/'backend',java_env)
    console('npm run dev -- --port 5173 --strictPort --open',local.REPO/'frontend')
    print('Keep the service consoles open. Open http://127.0.0.1:5173 .')
    print('Check readiness with: python scripts/app_local.py status')
    print('Existing database records and model artifacts were not initialized or deleted.')


if __name__=='__main__':
    try:
        if len(sys.argv)==2 and sys.argv[1]=='start':start()
        else:local.main()
    except KeyboardInterrupt:
        print('Stopped.');raise SystemExit(130)
    except (OSError,ValueError,KeyError,subprocess.CalledProcessError,getpass.GetPassWarning) as e:
        print('STOP:',e);raise SystemExit(2)
