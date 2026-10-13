<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { getWorkbench } from '../api/batch-workbench.js'
const props=defineProps({batchId:{type:[String,Number],required:true},refreshKey:{type:Number,default:0}})
const data=ref(null),error=ref('');let abort=null
async function load(){abort?.abort();const c=new AbortController();abort=c;error.value='';try{const r=await getWorkbench(props.batchId,{signal:c.signal});if(!c.signal.aborted)data.value=r}catch(e){if(!c.signal.aborted){data.value=null;error.value=e.message}}}
watch(()=>[props.batchId,props.refreshKey],load,{immediate:true});onBeforeUnmount(()=>abort?.abort())
</script>
<template><section class="e-flow-block"><div class="e-section-line"><div><strong>{{data?.headline || '批次品控工作台'}}</strong><p v-if="data" class="e-note">{{data.next_action.label}} · 拟定用途：{{data.use_label}}</p><p v-if="error" class="e-note">{{error}}</p></div><RouterLink class="btn secondary" :to="{path:`/batches/${batchId}`,query:{tab:data?.next_action.tab||'overview'}}">回到本批次工作台 →</RouterLink></div></section></template>
