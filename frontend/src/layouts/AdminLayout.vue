<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '../components/Icon.vue'
import ServiceStatus from '../components/ServiceStatus.vue'
import { modules } from '../config/modules.js'
import { moduleLocation, validId } from '../utils/batch.js'
import '../styles/engineering-v2.css'
const route = useRoute()
const currentId = computed(() => validId(route.params.id) || validId(route.query.batch_id))
function link(item) { return ['inspection', 'processing', 'coldchain', 'traceability'].includes(item.key) ? moduleLocation(item.path, currentId.value) : item.path }
</script>
<template>
  <div class="admin-app engineering-v2">
    <a class="e-skip" href="#main-content">跳到主要内容</a>
    <aside class="sidebar no-print">
      <RouterLink class="brand" to="/"><span class="brand-mark"><Icon name="leaf" /></span><span>智检鲜达<small>果蔬品质 · 全程可追溯</small></span></RouterLink>
      <div class="sidebar-label">批次业务工作台</div>
      <nav aria-label="主导航"><RouterLink v-for="item in modules" :key="item.key" :to="link(item)" class="nav-item" :class="{ selected: route.meta.section === item.key }"><Icon :name="item.icon" /><span>{{ item.name }}</span></RouterLink></nav>
      <div class="sidebar-bottom"><span class="prototype-dot" />统一品控版 · V3<p>检测有证据，复核有记录。<br />当前为受控本机演示环境。</p></div>
    </aside>
    <div class="workspace">
      <header class="topbar no-print"><div class="breadcrumb">业务工作台<span>/</span><strong>{{ route.meta.title }}</strong></div><div class="topbar-meta"><span class="badge neutral">人工复核协同</span><span v-if="currentId" class="muted">批次 ID {{ currentId }}</span></div></header>
      <main id="main-content" class="main-content"><ServiceStatus /><RouterView /></main>
      <footer class="app-footer no-print">智检鲜达 · 当前模型覆盖苹果擦伤与虫害损伤；无登录权限控制，仅限可信本地环境。工程联通不等于模型准确率或质量认证。</footer>
    </div>
  </div>
</template>
