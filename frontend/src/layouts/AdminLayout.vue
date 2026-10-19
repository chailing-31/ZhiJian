<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Icon from '../components/Icon.vue'
import ServiceStatus from '../components/ServiceStatus.vue'
import { modules } from '../config/modules.js'
import { validId } from '../utils/batch.js'
import '../styles/engineering-v2.css'
import '../styles/application.css'
const route = useRoute(), router = useRouter()
const currentId = computed(() => validId(route.params.id) || validId(route.query.batch_id))
const tabFor = { inspection: 'inspection', processing: 'processing', coldchain: 'coldchain', traceability: 'report' }
const tabNames = { overview: '品控总览', inspection: '照片质检', assessment: '检查与修正', processing: '加工品控', coldchain: '冷链监测', report: '报告与溯源' }
const pageTitle = computed(() => currentId.value && tabNames[route.query.tab] ? tabNames[route.query.tab] : route.meta.title)
const search = ref(''), menuOpen = ref(false), sidebar = ref(null), menuButton = ref(null)
const media = window.matchMedia('(max-width: 760px)'), mobile = ref(media.matches)
const today = new Intl.DateTimeFormat('zh-CN', { month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai' }).format(new Date())
function link(item) { return currentId.value && tabFor[item.key] ? { path: '/batches/' + currentId.value, query: { tab: tabFor[item.key] } } : item.path }
function active(item) {
  if (currentId.value && route.query.tab) return route.query.tab === 'overview' || route.query.tab === 'assessment' ? item.key === 'batches' : tabFor[item.key] === route.query.tab
  return route.meta.section === item.key
}
function find() { router.push({ path: '/batches', query: search.value.trim() ? { q: search.value.trim() } : {} }) }
function closeMenu() { menuOpen.value = false; if (mobile.value) nextTick(() => menuButton.value?.focus()) }
function resized(event) { mobile.value = event.matches; menuOpen.value = false }
function keyboard(event) {
  if (!menuOpen.value || !mobile.value) return
  if (event.key === 'Escape') { event.preventDefault(); closeMenu(); return }
  if (event.key !== 'Tab') return
  const controls = [...sidebar.value.querySelectorAll('a[href], button')].filter(e => !e.disabled)
  const first = controls[0], last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
watch(() => route.fullPath, () => { menuOpen.value = false })
watch(menuOpen, value => {
  document.body.style.overflow = value && mobile.value ? 'hidden' : ''
  if (value) nextTick(() => sidebar.value?.querySelector('button')?.focus())
})
onMounted(() => { media.addEventListener('change', resized); document.addEventListener('keydown', keyboard) })
onBeforeUnmount(() => { media.removeEventListener('change', resized); document.removeEventListener('keydown', keyboard); document.body.style.overflow = '' })
</script>
<template>
  <div class="admin-app engineering-v2 zj-application" data-interface-version="ui-usability-20261016" data-interface-revision="current-develop-66fbbd6">
    <a class="e-skip" href="#main-content">跳到主要内容</a>
    <button v-if="menuOpen && mobile" class="ui-nav-backdrop no-print" aria-label="关闭导航遮罩" @click="closeMenu" />
    <aside
        id="app-navigation"
        ref="sidebar"
        class="sidebar no-print"
        :class="{ 'mobile-open': menuOpen }"
        :inert="mobile && !menuOpen ? '' : null"
        :role="mobile ? 'dialog' : undefined"
        :aria-modal="mobile && menuOpen ? true : undefined"
        aria-label="业务导航"
    >
      <div class="ui-brand-row">
        <RouterLink class="brand" to="/" aria-label="智检鲜达首页">
          <span class="brand-mark"><Icon name="leaf" /></span>
          <span>
        智检鲜达
        <small>苹果品控与全程追溯</small>
      </span>
        </RouterLink>
        <button
            class="ui-icon-button ui-mobile-only"
            aria-label="关闭导航"
            @click="closeMenu"
        >
          <Icon name="close" />
        </button>
      </div>

      <div class="sidebar-label">批次业务工作台</div>

      <nav aria-label="主导航">
        <template v-for="(item, index) in modules" :key="item.key">
          <div v-if="index === 2" class="sidebar-label ui-nav-group">
            业务管理
          </div>
          <RouterLink
              :to="link(item)"
              class="nav-item"
              :class="{ selected: active(item) }"
              :aria-current="active(item) ? 'page' : undefined"
              :title="item.name"
          >
            <Icon :name="item.icon" />
            <span>{{ item.name }}</span>
          </RouterLink>
        </template>
      </nav>

      <div class="ui-sidebar-context">
        <RouterLink
            v-if="currentId"
            :to="{ path: '/batches/' + currentId, query: { tab: 'overview' } }"
        >
          <Icon name="back" />
          返回批次总览
        </RouterLink>
        <RouterLink v-else :to="{ path: '/batches', query: { new: '1' } }">
          <Icon name="plus" />
          新建批次
        </RouterLink>
      </div>
      <div class="sidebar-bottom">质检 · 加工 · 冷链 · 溯源</div>
    </aside>
    <div class="workspace" :inert="mobile && menuOpen ? '' : null">
      <header class="topbar no-print">
        <div class="ui-topbar-start"><button ref="menuButton" class="ui-icon-button ui-mobile-only" :aria-expanded="menuOpen" aria-controls="app-navigation" aria-label="打开导航" @click="menuOpen = !menuOpen"><Icon name="menu" /></button><RouterLink class="ui-mobile-brand" to="/">智检鲜达</RouterLink><div class="breadcrumb"><RouterLink to="/">工作台</RouterLink><Icon name="chevron" /><strong>{{ pageTitle }}</strong></div></div>
        <form class="ui-global-search" role="search" @submit.prevent="find"><Icon name="search" /><input v-model="search" type="search" aria-label="全局搜索批次" placeholder="搜索批次、品种或产地" /><button type="submit" aria-label="搜索批次"><Icon name="arrow" /></button></form>
        <div class="topbar-meta"><span class="ui-today">{{ today }}</span><details class="zj-service-settings"><summary><Icon name="sliders" /><span>服务连接</span></summary><div><ServiceStatus /></div></details></div>
      </header>
      <main id="main-content" class="main-content"><RouterView /></main>
      <footer class="app-footer no-print">智检鲜达 · 每一步，都有记录可查</footer>
    </div>
  </div>
</template>
