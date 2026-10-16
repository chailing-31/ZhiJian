import test from 'node:test'
import assert from 'node:assert/strict'
import { reportStatusFor, attentionFor } from '../src/utils/presentation.js'
import {assessmentProblem, evidenceDraft,steps,writeIntent,processNumber,canAdopt,toZonedTime} from '../src/utils/batch-workbench.js'
const e={record_count:3,pending_count:0,confirmed_target_records:1}
test('published disposition has no processing todo, but real alerts and stale publication remain visible', () => {
  const w = { state: 'DISPOSITION_RECORDED', assessment_current: true, assessment: { final_grade: 'REJECT' }, quality_disposition_recorded: true, coldchain: { hold: false }, processing: { record_id: 5, current: false }, publication: { current: true } }
  assert.equal(attentionFor(w), null)
  assert.equal(reportStatusFor(w).complete, true)
  assert.equal(attentionFor({ ...w, publication: { current: false } }).tab, 'report')
  assert.equal(attentionFor({ ...w, coldchain: { hold: true } }).tab, 'coldchain')
})
test('registered disposition report does not send the reader back to view the same report', () => {
  const state = reportStatusFor({ state: 'DISPOSITION_RECORDED', coldchain: { hold: false }, next_action: { label: '查看品质处置报告' } })
  assert.equal(state.complete, true)
  assert.equal(state.title, '品质处置已登记')
})
test('real pending work remains visible even with a saved disposition', () => {
  const state = reportStatusFor({ state: 'COLDCHAIN_REVIEW', quality_disposition_recorded: true, coldchain: { hold: true }, next_action: { label: '核查冷链异常与数据完整性' } })
  assert.equal(state.complete, false)
  assert.equal(state.title, '核查冷链异常与数据完整性')
  assert.equal(reportStatusFor({ state: 'REJECTED' }).complete, false)
  assert.equal(reportStatusFor({ state: 'MONITORING' }).title, '已进入报告阶段')
})
test('A cannot be approved with confirmed target defects',()=>assert.ok(assessmentProblem('A','premium_sorting',e)))
test('B supports the general sorting path',()=>assert.equal(assessmentProblem('B','general_sorting',e),''))
test('C can only enter evaluation',()=>{assert.ok(assessmentProblem('C','general_sorting',e));assert.equal(assessmentProblem('C','processing_evaluation',e),'')})
test('only ABC REJECT are business grades',()=>{assert.ok(assessmentProblem('D','isolate_return',e));assert.equal(assessmentProblem('REJECT','isolate_return',e),'')})
test('reject disposition is not ordinary sale',()=>assert.ok(assessmentProblem('REJECT','general_sorting',e)))
test('empty evidence does not yield A',()=>assert.ok(assessmentProblem('A','premium_sorting',{record_count:0})))
test('explicitly marked recheck holds the workflow',()=>assert.ok(assessmentProblem('B','general_sorting',{...e,pending_count:1})))
test('no unknown use accepted',()=>assert.ok(assessmentProblem('B','juice',e)))
test('evidence draft contains scope rather than fabricated fruit rate',()=>{const s=evidenceDraft({evidence_summary:'3条图片记录',round:{sampling_note:'指定箱位'}});assert.ok(s.includes('指定箱位'));assert.ok(!s.includes('%'))})
test('idempotent retry retains key for identical body',()=>{let n=0;const f=writeIntent(()=>String(++n));assert.equal(f({a:1}).request_id,f({a:1}).request_id)})
test('changed input gets new idempotency key',()=>{let n=0;const f=writeIntent(()=>String(++n));assert.notEqual(f({a:1}).request_id,f({a:2}).request_id)})
test('missing temperature does not become zero',()=>assert.throws(()=>processNumber('','温度')))
test('optional material temperature stays null',()=>assert.equal(processNumber('','原料温度',false),null))
test('nonfinite condition rejected',()=>assert.throws(()=>processNumber(Infinity,'温度')))
test('zero temperature is valid explicit input',()=>assert.equal(processNumber(0,'温度'),0))
const w={assessment_current:true,assessment:{final_grade:'B',intended_use:'general_sorting'},sorting_recorded:true,processing:{current:true,advice_status:'suggested'},coldchain:{hold:false}}
test('adoption only after sorting and suggested rule',()=>assert.equal(canAdopt(w),true))
test('source change blocks adoption',()=>assert.equal(canAdopt({...w,assessment_current:false}),false))
test('manual review status cannot be adopted',()=>assert.equal(canAdopt({...w,processing:{current:true,advice_status:'manual_review'}}),false))
test('coldchain hold blocks adoption',()=>assert.equal(canAdopt({...w,coldchain:{hold:true}}),false))
test('no sorting is not completed processing',()=>assert.equal(canAdopt({...w,sorting_recorded:false}),false))
test('null workbench not treated as usable',()=>assert.equal(canAdopt(null),false))
test('stage display does not claim no coldchain data are normal',()=>{const s=steps({round:null,evidence:{},assessment:null,processing:{},coldchain:{reading_count:0}});assert.equal(s[3].text,'尚无观测记录')})
test('stale published snapshot remains stale',()=>assert.equal(steps({evidence:{},processing:{},coldchain:{},publication:{current:false}})[4].text,'公开摘要待更新'))
test('execution requires separate real record',()=>assert.equal(steps({...w,processing:{current:true,adopted:true}})[2].text,'已采用，待执行记录'))
test('invalid actual execution time rejected',()=>assert.throws(()=>toZonedTime('invalid')))
