import test from 'node:test'
import assert from 'node:assert/strict'
import { decisionFor, normalizedGrade, reviewProblem, candidateCaption, stageCounts, validPredictionGeometry } from '../src/utils/engineering.js'
const prediction = { detections: [], suggested_grade: null }
test('no photo never produces a grade', () => { assert.equal(decisionFor(null).grade, null); assert.equal(decisionFor(null).canProcess, false) })
test('no detections never imply A', () => { const d=decisionFor({prediction}); assert.equal(d.grade,null); assert.equal(d.canProcess,false) })
test('rule grades are not human grades', () => { const d=decisionFor({prediction:{...prediction,suggested_grade:'B'}}); assert.equal(d.source,'model_rule'); assert.equal(d.canProcess,false) })
test('a model-produced A is not accepted by display policy', () => assert.equal(decisionFor({prediction:{...prediction,suggested_grade:'A'}}).grade,null))
test('saved B review can be proposed as an explicitly approved source', () => { const d=decisionFor({prediction,latest_review:{final_grade:'B',conclusion:'target_confirmed'}}); assert.equal(d.source,'human'); assert.equal(d.canProcess,true) })
test('D is never silently mapped to REJECT', () => { const d=decisionFor({prediction,latest_review:{final_grade:'D',conclusion:'target_confirmed'}}); assert.equal(d.grade,null); assert.equal(d.source,'legacy_grade'); assert.equal(d.canProcess,false) })
test('needs_recheck cannot be used even with a saved A', () => assert.equal(decisionFor({prediction,latest_review:{final_grade:'A',conclusion:'needs_recheck'}}).canProcess,false))
test('invalid or missing review conclusion cannot be used', () => assert.equal(decisionFor({prediction,latest_review:{final_grade:'A'}}).canProcess,false))
test('only explicitly supported grade tokens are normalized', () => { assert.equal(normalizedGrade(' b '),'B'); assert.equal(normalizedGrade('优质'),null) })
test('candidate numbers are one-based ordinals, not class IDs', () => { assert.match(candidateCaption({class_id:0,class_label:'擦伤',confidence:.8},1),/^2 ·/); assert.match(candidateCaption({class_id:1,class_label:'虫害',confidence:.8},0),/^1 ·/) })
test('rejected or duplicated boxes must remain accounted for', () => { const form={reviewer:'Test',remark:'Test',conclusion:'needs_recheck',final_grade:'',candidates:[{candidate_index:1,decision:'duplicate',duplicate_of:1}]}; assert.notEqual(reviewProblem(form,1),'') })
test('uncertain decisions force needs_recheck', () => { const f={reviewer:'Test',remark:'Test',conclusion:'no_target_confirmed',final_grade:'',candidates:[{candidate_index:1,decision:'uncertain'}]}; assert.notEqual(reviewProblem(f,1),''); f.conclusion='needs_recheck'; assert.equal(reviewProblem(f,1),'') })
test('empty detections support honest review without a fabricated box', () => assert.equal(reviewProblem({reviewer:'Test',remark:'Real review',conclusion:'no_target_confirmed',final_grade:'',candidates:[]},0),''))
test('geometry uses original normalized image pixels', () => { const p={image:{width:400,height:300},detections:[{bbox_xyxy:[10,20,390,299]}]}; assert.equal(validPredictionGeometry(p),true); p.detections[0].bbox_xyxy=[10,20,401,299]; assert.equal(validPredictionGeometry(p),false) })
test('invalid or NaN geometry is not rendered', () => { assert.equal(validPredictionGeometry({image:{width:100,height:100},detections:[{bbox_xyxy:[0,0,NaN,30]}]}),false); assert.equal(validPredictionGeometry(null),false) })
test('flow counters do not invent missing records', () => assert.deepEqual(stageCounts(null),{inspections:0,reviewed:0,processing:0,readings:0}))

test('needs_recheck may not carry a final business grade',()=>assert.notEqual(reviewProblem({reviewer:'Test',remark:'Test',conclusion:'needs_recheck',final_grade:'B',candidates:[]},0),''))
test('new grade vocabulary excludes D',()=>assert.equal(normalizedGrade('D'),null))
test('high burden is held instead of mapped to rejection',()=>{const d=decisionFor({prediction:{detections:[{}],suggested_grade:null,grade_status:'withheld_requires_review'}});assert.equal(d.grade,null);assert.equal(d.canProcess,false);assert.match(d.title,/复核/)})
test('legacy A with confirmed defects is not forwarded',()=>assert.equal(decisionFor({prediction,latest_review:{final_grade:'A',conclusion:'target_confirmed'}}).canProcess,false))

// Manual findings remain separate from model candidate detections.
test('manual finding can be reviewed without fabricating a model box',()=>assert.equal(reviewProblem({reviewer:'Test',remark:'人工在苹果右下方发现擦伤，模型漏检，复核定为一般外观。',conclusion:'manual_target_confirmed',final_grade:'B',candidates:[]},0),''))
test('manual finding requires a substantive written description',()=>assert.notEqual(reviewProblem({reviewer:'Test',remark:'test',conclusion:'manual_target_confirmed',final_grade:'B',candidates:[]},0),''))
test('manual finding never qualifies for A',()=>assert.notEqual(reviewProblem({reviewer:'Test',remark:'人工在苹果右下方发现擦伤，模型漏检。',conclusion:'manual_target_confirmed',final_grade:'A',candidates:[]},0),''))
test('saved manual B finding has human provenance',()=>{const d=decisionFor({prediction,latest_review:{final_grade:'B',conclusion:'manual_target_confirmed'}});assert.equal(d.source,'human');assert.equal(d.canProcess,true)})
