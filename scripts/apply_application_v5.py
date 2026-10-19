"""Guarded source upgrade from accepted V4/V5 packages. No Git resets or database operations."""
from __future__ import annotations
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import os
import sys
import zipfile

SOURCE=Path(__file__).resolve().parents[1]
DEFAULT_TARGET=r'D:\ZhiJianData\ZhiJian-repro-test'

def sha(raw):return hashlib.sha256(raw).hexdigest()

def apply(target,check_only=False):
    target=Path(target).expanduser().resolve()
    if target==SOURCE.resolve():raise ValueError('This is already the complete V5.1 source folder. Run START_APP.cmd here, or choose your existing V4/V5 repository.')
    if not (target/'backend/pom.xml').is_file() or not (target/'frontend/package.json').is_file():
        raise ValueError('The target must be the existing project root containing backend and frontend.')
    manifest=json.loads((SOURCE/'upgrade-manifest-v5.json').read_text(encoding='utf-8'))
    changes=[];conflicts=[]
    for entry in manifest['files']:
        rel=Path(entry['path'])
        if rel.is_absolute() or '..' in rel.parts or ':' in entry['path'] or '\\' in entry['path']:raise ValueError('Invalid manifest path.')
        source=(SOURCE/rel).read_bytes()
        if sha(source)!=entry['new_sha256']:raise ValueError('Package file checksum failed: '+entry['path'])
        destination=target/rel
        if destination.exists() and not destination.is_file():raise ValueError('Target path is not a file: '+entry['path'])
        old=destination.read_bytes() if destination.is_file() else None
        if old is not None and (sha(old)==entry['new_sha256'] or entry.get('text_file') and sha(old.replace(b'\r\n',b'\n'))==entry['new_lf_sha256']):continue
        if old is None:
            if entry['base_sha256'] is not None and not entry.get('allow_missing',False):conflicts.append(entry['path']+' (missing baseline file)');continue
        else:
            accepted=[{'sha256':entry['base_sha256'],'lf_sha256':entry.get('base_lf_sha256')}]+entry.get('accepted_previous',[])
            old_sha=sha(old);old_lf_sha=sha(old.replace(b'\r\n',b'\n'))
            if not any(old_sha==item.get('sha256') or entry.get('text_file') and old_lf_sha==item.get('lf_sha256') for item in accepted):
                conflicts.append(entry['path']+' (local change or different baseline)');continue
        changes.append((rel,source,old))
    if conflicts:
        print('No files changed. These files differ from the accepted V4/V5 packages:')
        for item in conflicts:print('  '+item)
        raise ValueError('Use the complete V5.1 source folder, or reconcile your local changes before upgrading. No automatic reset.')
    print('Source baseline:',manifest['base_commit'],'Pending files:',len(changes))
    if check_only:return {'checked':True,'changed':0,'pending':len(changes)}
    if not changes:return {'checked':True,'changed':0,'message':'Already installed.'}
    backup=target/'v5-upgrade-backups'/datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    backup.mkdir(parents=True)
    with zipfile.ZipFile(backup/'source-before-v5.zip','w',zipfile.ZIP_DEFLATED) as z:
        for rel,new,old in changes:
            if old is not None:z.writestr(rel.as_posix(),old)
        z.writestr('upgrade-record.json',json.dumps({'version':manifest['version'],'base_commit':manifest['base_commit'],'new_files':[rel.as_posix() for rel,new,old in changes if old is None]},indent=2))
    written=[]
    try:
        for rel,new,old in changes:
            dst=target/rel
            current=dst.read_bytes() if dst.is_file() else None
            if current!=old:raise ValueError('A target file changed during the upgrade: '+rel.as_posix())
            dst.parent.mkdir(parents=True,exist_ok=True)
            temporary=dst.with_name(dst.name+'.zj-v5.tmp')
            try:temporary.write_bytes(new);os.replace(temporary,dst)
            finally:temporary.unlink(missing_ok=True)
            written.append((dst,old))
    except Exception:
        for dst,old in reversed(written):
            if old is None:dst.unlink(missing_ok=True)
            else:dst.write_bytes(old)
        raise
    return {'checked':True,'changed':len(written),'backup':str(backup/'source-before-v5.zip'),'database_changed':False}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--target');p.add_argument('--check',action='store_true');args=p.parse_args()
    try:
        target=args.target or input('Existing V4/V5 project root (Enter='+DEFAULT_TARGET+'): ').strip() or DEFAULT_TARGET
        print(json.dumps(apply(target,args.check),ensure_ascii=False,indent=2))
        print('Upgrade complete. Restart Java and Vue, then run START_APP.cmd. Database and image storage were not reset.')
    except (OSError,ValueError,KeyError) as e:
        print('STOP:',e);raise SystemExit(2)
