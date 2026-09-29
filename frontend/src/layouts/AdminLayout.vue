<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import { modules } from '../config/modules.js'
import { moduleLocation, validId } from '../utils/batch.js'
const route = useRoute()
const currentId = computed(() => validId(route.params.id) || validId(route.query.batch_id))
function link(item) { return ['inspection', 'processing', 'coldchain', 'traceability'].includes(item.key) ? moduleLocation(item.path, currentId.value) : item.path }
</script>
<template>
  <div class="admin-app">
    <aside class="sidebar no-print">
      <RouterLink class="brand" to="/"><span class="brand-mark"><Icon name="leaf" /></span><span>智检鲜达<small>果蔬批次品控平台</small></span></RouterLink>
      <div class="sidebar-label">业务工作台</div>
      <nav aria-label="主导航"><RouterLink v-for="item in modules" :key="item.key" :to="link(item)" class="nav-item" :class="{ selected: route.meta.section === item.key }"><Icon :name="item.icon" /><span>{{ item.name }}</span><span v-if="item.tone === 'pending'" class="nav-pending" aria-label="待接入" /></RouterLink></nav>
      <div class="sidebar-bottom"><span class="prototype-dot" />实验室原型 · v0.2<p>同一批次，连接每一道记录。<br />未接入功能不生成业务结果。</p></div>
    </aside>
    <div class="workspace">
      <header class="topbar no-print"><div class="breadcrumb">工作台<span>/</span><strong>{{ route.meta.title }}</strong></div><div class="topbar-meta"><span class="badge neutral">开发演示环境</span><span class="muted">烟台苹果 · 批次流程</span></div></header>
      <main id="main-content" class="main-content"><RouterView /></main>
      <footer class="app-footer no-print">智检鲜达 · 管理端仅用于可信开发环境，登录与权限控制尚未接入。</footer>
    </div>
  </div>
</template>
