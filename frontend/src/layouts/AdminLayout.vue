<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import ServiceStatus from '../components/ServiceStatus.vue'
import { modules } from '../config/modules.js'
import { validId } from '../utils/batch.js'
import '../styles/engineering-v2.css'
import '../styles/application.css'
const route = useRoute()
const currentId = computed(() => validId(route.params.id) || validId(route.query.batch_id))
const tabFor = { inspection: 'inspection', processing: 'processing', coldchain: 'coldchain', traceability: 'report' }
function link(item) { return currentId.value && tabFor[item.key] ? { path: `/batches/${currentId.value}`, query: { tab: tabFor[item.key] } } : item.path }
function active(item) { return currentId.value && route.query.tab ? tabFor[item.key] === route.query.tab : route.meta.section === item.key }
</script>
<template>
  <div class="admin-app engineering-v2 zj-application">
    <a class="e-skip" href="#main-content">跳到主要内容</a>
    <aside class="sidebar no-print">
      <RouterLink class="brand" to="/"><span class="brand-mark"><Icon name="leaf" /></span><span>智检鲜达<small>苹果品控与全程追溯</small></span></RouterLink>
      <div class="sidebar-label">批次业务工作台</div>
      <nav aria-label="主导航"><RouterLink v-for="item in modules" :key="item.key" :to="link(item)" class="nav-item" :class="{ selected: active(item) }"><Icon :name="item.icon" /><span>{{ item.name }}</span></RouterLink></nav>
      <div class="sidebar-bottom"><span class="prototype-dot" />批次协同品控<p>AI 初评 · 作业记录<br />冷链处置 · 扫码溯源</p></div>
    </aside>
    <div class="workspace">
      <header class="topbar no-print"><div class="breadcrumb">业务工作台<span>/</span><strong>{{ route.meta.title }}</strong></div><div class="topbar-meta"><details class="zj-service-settings"><summary>服务连接</summary><div><ServiceStatus /></div></details><span v-if="currentId" class="muted">批次 ID {{ currentId }}</span></div></header>
      <main id="main-content" class="main-content"><RouterView /></main>
      <footer class="app-footer no-print">智检鲜达 · 每一步，都有记录可查</footer>
    </div>
  </div>
</template>
