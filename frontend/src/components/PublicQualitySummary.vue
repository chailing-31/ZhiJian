<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import { publicSummary } from '../api/batch-workbench.js'
const props=defineProps({batchCode:{type:String,required:true}})
const data=ref(null),error=ref('');let abort=null
watch(()=>props.batchCode,async code=>{abort?.abort();const c=new AbortController();abort=c;data.value=null;error.value='';try{const r=await publicSummary(code,{signal:c.signal});if(!c.signal.aborted)data.value=r}catch(e){if(!c.signal.aborted)error.value='本次批次评定摘要暂时无法读取，请稍后刷新。'}},{immediate:true})
onBeforeUnmount(()=>abort?.abort())
</script>
<template><section class="card"><h2>已批准的批次品控摘要</h2><p v-if="error" role="alert">{{error}}</p><p v-else-if="data && !data.published">尚未批准公开本次批次评定摘要。</p><template v-else-if="data?.published"><p>{{data.snapshot.quality_summary}}</p><p>{{data.snapshot.evidence_scope}}</p><p v-if="data.snapshot.grade"><strong>人工业务等级：</strong>{{data.snapshot.grade}}</p><p v-if="data.snapshot.intended_use"><strong>拟定用途：</strong>{{data.snapshot.intended_use}}</p><p>{{data.snapshot.processing_summary}}</p><p>{{data.snapshot.coldchain_summary}}</p><p v-for="s in data.snapshot.coldchain_sources" :key="s.source">{{s.source==='sensor'?'传感器来源':'模拟数据'}}：{{s.reading_count}} 条</p><p class="footnote">发布时间：{{data.published_at}} · 评定第 {{data.snapshot.assessment_revision}} 版</p><p v-if="!data.current" class="notice warning">这是已批准的历史快照，后续记录已经变化，尚未重新发布。</p><p class="footnote">{{data.snapshot.scope_note}}</p></template></section></template>
