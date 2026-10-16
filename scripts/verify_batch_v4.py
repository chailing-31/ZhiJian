"""Read-only by default; --write creates a NEW Q4-* labelled integration-test batch only."""
from __future__ import annotations
import argparse
from datetime import datetime,timedelta,timezone
import html
import json
import mimetypes
from pathlib import Path
import sys
import urllib.error
import urllib.request
from uuid import uuid4
BASE='http://127.0.0.1:8080'
OPENER=urllib.request.build_opener(urllib.request.ProxyHandler({}))
ROWS=[]

def call(method,path,body=None,expected=200,raw=None,headers=None):
    data=raw if raw is not None else None if body is None else json.dumps(body,ensure_ascii=False).encode('utf-8')
    h={'Accept':'application/json',**(headers or {})}
    if body is not None:h['Content-Type']='application/json'
    req=urllib.request.Request(BASE+path,data=data,headers=h,method=method)
    try:
        with OPENER.open(req,timeout=300) as response:status=response.status;payload=response.read()
    except urllib.error.HTTPError as e:status=e.code;payload=e.read()
    result=json.loads(payload.decode('utf-8')) if payload else None
    if status!=expected:raise RuntimeError(f'{method} {path}: HTTP {status}, expected {expected}; {str(result)[:600]}')
    return result

def checked(name,condition):
    ROWS.append({'name':name,'passed':bool(condition)})
    if not condition:raise RuntimeError('FAILED: '+name)
    print('PASS:',name)
def identity(body):return {**body,'request_id':str(uuid4())}
def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--write',action='store_true');ap.add_argument('--image',type=Path);ap.add_argument('--output',type=Path);args=ap.parse_args()
    out=args.output or Path(r'D:\ZhiJianData\batch-v4')/datetime.now().strftime('%Y%m%d-%H%M%S-%f')
    out.mkdir(parents=True,exist_ok=False)
    report={'scope':'labelled_engineering_acceptance_not_quality_assessment','real_model_accuracy_test':False,'write_requested':args.write,'checks':ROWS,'state':'RUNNING'}
    try:
        s=call('GET','/workspace/status');checked('V4 source version',s.get('patch_version')=='batch-workbench-v4-20261013')
        checked('Q4 additive tables ready',s.get('batch_workbench_ready') is True)
        checked('isolated review database',s.get('database_name')=='smart_fresh_c_review')
        checked('Java can call AI',call('GET','/inspection-service/ready').get('ready') is True)
        checked('Java can call B',call('GET','/business-service/ready').get('ready') is True)
        if not args.write:report['state']='READ_ONLY_CONNECTIONS_PASSED';return
        if not args.image or not args.image.is_file():raise ValueError('A readable original test image is required.')
        content=args.image.read_bytes();mime=mimetypes.guess_type(args.image.name)[0]
        if mime not in {'image/jpeg','image/png','image/webp'} or not 0<len(content)<=10*1024*1024:raise ValueError('Use a nonempty JPEG/PNG/WebP up to 10 MiB.')
        print('Creates a NEW Q4-* test batch. AI is real; review decisions, B grade, operations and temperatures are TEST FIXTURES.')
        print('Does not edit existing batches. Test records remain in the isolated DB for inspection.')
        if input('Type WRITE_TEST_FIXTURES to authorize; Enter cancels: ').strip()!='WRITE_TEST_FIXTURES':report['state']='CANCELLED_NO_WRITES';return
        code='Q4-'+datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid4().hex[:6]
        b=call('POST','/batches',{'batch_code':code,'product':'苹果-工程测试','variety':'TEST FIXTURE','origin':'TEST ONLY','supplier':'TEST ONLY'})
        bid=b['batch_id'];prefix=f'/batches/{bid}';report['test_batch']=b
        w=call('GET',prefix+'/batch-workbench')
        r=call('POST',prefix+'/inspection-rounds',identity({'name':'工程测试轮次','sampling_note':'TEST FIXTURE ONLY：本轮是接口和持久化测试，不是实际抽检或模型效果成绩。','operator':'Q4 TEST','expected_round_id':0,'new_round_confirmed':True}))
        checked('round persisted and read back',call('GET',prefix+'/batch-workbench')['round']['id']==r['id'])
        boundary='q4-'+uuid4().hex
        raw=(f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="test-image"\r\nContent-Type: {mime}\r\n\r\n'.encode()+content+f'\r\n--{boundary}--\r\n'.encode())
        key=str(uuid4());headers={'Content-Type':'multipart/form-data; boundary='+boundary,'Idempotency-Key':key}
        image=call('POST',prefix+'/inspections',raw=raw,headers=headers)
        iid=image['inspection_id'];report['inspection_id']=iid
        duplicate=call('POST',prefix+'/inspections',raw=raw,headers=headers)
        checked('real upload retry remains one inspection',duplicate['inspection_id']==iid)
        w=call('GET',prefix+'/batch-workbench');checked('upload enrolled in active round',w['evidence']['record_count']==1)
        review={'expected_revision':image['review_revision'],'reviewer':'Q4 TEST FIXTURE','remark':'TEST FIXTURE ONLY：候选判断为接口测试占位，不代表实际人工复核或模型准确性。','conclusion':'no_target_confirmed','final_grade':None,'publish_summary':False,'candidate_reviews':[{'candidate_index':i+1,'decision':'false_positive','duplicate_of':None,'note':'TEST FIXTURE, not ground truth'} for i in range(len(image['prediction']['detections']))]}
        back=call('PATCH',f'/inspections/{iid}/review',review);checked('review persisted',back['review_revision']==1)
        w=call('GET',prefix+'/batch-workbench')
        assess=identity({'round_id':w['round']['id'],'expected_assessment_id':0,'expected_fingerprint':w['evidence']['fingerprint'],'final_grade':'B','intended_use':'general_sorting','grade_basis':'TEST FIXTURE ONLY：用于验证批次评定传给 B 的接口，不是该水果的真实等级。','operator':'Q4 TEST','scope_confirmed':True,'use_confirmed':True})
        ar=call('POST',prefix+'/batch-assessments',assess)
        checked('assessment repeat is idempotent',call('POST',prefix+'/batch-assessments',assess)['id']==ar['id'])
        w=call('GET',prefix+'/batch-workbench');a=w['assessment'];checked('batch B current',w['assessment_current'] and a['final_grade']=='B')
        advice=identity({'assessment_id':a['id'],'assessment_revision':a['revision'],'grade':'A','operator':'Q4 TEST','use_as_process_input_confirmed':True,'temperature':5,'humidity':80,'material_temperature':None})
        pr=call('POST',prefix+'/assessment-processing-advice',advice);w=call('GET',prefix+'/batch-workbench');p=w['processing']
        checked('server used saved B, not client grade A',p['inputs']['grade']=='B' and p['inputs']['grade_source']['assessment_id']==a['id'])
        checked('actual B returned suggested with rule version',p['advice_status']=='suggested' and bool(p['advice'].get('rule_version')))
        checked('advice retry idempotent',call('POST',prefix+'/assessment-processing-advice',advice)['id']==pr['id'])
        adoption={'operator':'Q4 TEST','note':'TEST FIXTURE ONLY：采用值为接口测试，非真实设备参数。','adopted_values':p['advice']['advice'],'expected_processing_fingerprint':p['fingerprint']}
        call('POST',f"/processing-records/{p['record_id']}/adopt",adoption,expected=409);checked('cannot bypass missing sorting record',True)
        now=datetime.now(timezone.utc).replace(microsecond=0)
        operation=identity({'assessment_id':a['id'],'assessment_revision':a['revision'],'operator':'Q4 TEST','kind':'sorting_completed','performed_at':now.isoformat(),'note':'TEST FIXTURE ONLY：模拟已执行状态以测试流程；并无真实分选作业。','actually_performed_confirmed':True})
        call('POST',prefix+'/batch-operations',operation)
        call('POST',f"/processing-records/{p['record_id']}/adopt",adoption)
        call('POST',f"/processing-records/{p['record_id']}/adopt",adoption,expected=409);checked('optimistic adoption prevents stale overwrite',True)
        w=call('GET',prefix+'/batch-workbench');p=w['processing'];checked('adoption readback and immutable history',p['adopted'] and len(w['adoption_history'])==1)
        execution=identity({**{k:v for k,v in operation.items() if k!='request_id'},'kind':'processing_completed','processing_id':p['record_id'],'expected_processing_fingerprint':p['fingerprint'],'note':'TEST FIXTURE ONLY：模拟加工执行状态，不是设备已运行。'})
        call('POST',prefix+'/batch-operations',execution);checked('execution separate from adoption',call('GET',prefix+'/batch-workbench')['processing_executed'])
        # Continuous B rule observations are strictly timestamped simulated data, not sensor readings.
        for i,temp in enumerate([5,10,10,10,10,10,10]):
            ts=now-timedelta(minutes=20-i)
            call('POST',prefix+'/sensor-readings',{'timestamp':ts.isoformat(),'temperature':temp,'humidity':80,'source':'simulation'})
        cold=call('GET',prefix+'/coldchain');checked('continuous overheating produced an episode',bool(cold['episodes']) and bool(cold['alerts']))
        for al in cold['alerts']:call('POST',f"/alerts/{al['alert_id']}/resolve",{'resolved_by':'Q4 TEST','resolution':'TEST FIXTURE ONLY：测试处置记录，不代表温度恢复。'})
        checked('resolved alert does not erase ongoing observations',call('GET',prefix+'/batch-workbench')['coldchain']['hold'])
        call('POST',prefix+'/sensor-readings',{'timestamp':(now-timedelta(minutes=13)).isoformat(),'temperature':5,'humidity':80,'source':'simulation'})
        w=call('GET',prefix+'/batch-workbench');checked('recovery preserved and sources distinguished',w['coldchain']['ongoing_episodes']==0 and w['coldchain']['sources'][0]['reading_count']==0 and w['coldchain']['sources'][1]['reading_count']==8)
        options={'publish_grade':False,'publish_use':False};preview=call('POST',prefix+'/batch-summary/preview',options)
        publication=identity({**options,'expected_fingerprint':preview['source_fingerprint'],'confirm_public_summary':True,'operator':'Q4 TEST'})
        call('POST',prefix+'/batch-summary/publish',publication);pub=call('GET',f'/trace/{code}/batch-summary');checked('published snapshot current',pub['published'] and pub['current'])
        snapshot=json.dumps(pub['snapshot'],ensure_ascii=False)
        checked('public snapshot omits private fields',all(k not in snapshot for k in ['reviewer','approved_by','image_hash','candidate_reviews','weights_sha256','Q4 TEST']) and 'grade' not in pub['snapshot'])
        review['expected_revision']=1;review['remark']='TEST FIXTURE ONLY：新复核版本，用于检查历史批次评定和公开快照过期。'
        call('PATCH',f'/inspections/{iid}/review',review)
        checked('review update invalidates batch assessment',not call('GET',prefix+'/batch-workbench')['assessment_current'])
        changed_advice={**advice,'request_id':str(uuid4())};call('POST',prefix+'/assessment-processing-advice',changed_advice,expected=409);checked('stale assessment cannot generate new advice',True)
        checked('publication becomes historical, not silently rewritten',not call('GET',f'/trace/{code}/batch-summary')['current'])
        report_view=call('GET',prefix+'/report');checked('report includes same assessment and staleness',report_view['batch_workbench']['assessment']['id']==a['id'] and not report_view['batch_workbench']['assessment_current'])
        report['state']='LABELLED_WRITE_ACCEPTANCE_PASSED'
    except Exception as exc:
        report['state']='FAILED';report['error']=str(exc);print('STOP:',exc);raise
    finally:
        (out/'acceptance.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        text='<meta charset="utf-8"><title>Q4 engineering acceptance</title><h1>'+html.escape(report['state'])+'</h1><p>工程测试，不是模型准确率、真实作业或产品等级证明。</p><pre>'+html.escape(json.dumps(report,ensure_ascii=False,indent=2))+'</pre>'
        (out/'index.html').write_text(text,encoding='utf-8');print('ACCEPTANCE_REPORT:',out/'acceptance.json')
if __name__=='__main__':
    try:main()
    except (OSError,ValueError,RuntimeError,urllib.error.URLError,KeyError) as e:print('STOP:',e);raise SystemExit(2)
