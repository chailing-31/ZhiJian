<script setup>
import { onBeforeUnmount,ref,watch } from 'vue'
import { getWorkbench } from '../../api/batch-workbench.js'
import AssessmentProcessing from '../../components/batch/AssessmentProcessing.vue'
import BatchFlowStatus from '../../components/BatchFlowStatus.vue'
import '../../styles/batch-workbench.css'
const props=defineProps({batch:{type:Object,required:true}})
const data=ref(null),error=ref('');let abort=null
async function load(){abort?.abort();const c=new AbortController();abort=c;error.value='';try{const r=await getWorkbench(props.batch.batch_id,{signal:c.signal});if(!c.signal.aborted)data.value=r}catch(e){if(!c.signal.aborted){data.value=null;error.value=e.message}}}
watch(()=>props.batch.batch_id,load,{immediate:true});onBeforeUnmount(()=>abort?.abort())
</script>
<template><div class="q4-workbench"><BatchFlowStatus :batch-id="batch.batch_id" :refresh-key="data?.assessment?.revision||0" /><p v-if="error" class="notice danger">{{error}}</p><button class="btn secondary" @click="load">刷新批次评定与工艺</button><AssessmentProcessing v-if="data" :key="batch.batch_id" :workbench="data" @changed="data=$event" /></div></template>
