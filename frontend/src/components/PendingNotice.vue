<script setup>
defineProps({ text: { type: String, required: true } })
</script>
<template><div class="notice pending-notice"><span class="badge pending">待接入</span><p>{{ text }}</p></div></template>
