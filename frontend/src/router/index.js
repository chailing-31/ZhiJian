import { createRouter, createWebHistory } from 'vue-router'
import AdminLayout from '../layouts/AdminLayout.vue'

export const routes = [
  {
    path: '/', component: AdminLayout,
    children: [
      { path: '', name: 'dashboard', component: () => import('../pages/Dashboard.vue'), meta: { title: '首页', section: 'dashboard' } },
      { path: 'batches', name: 'batches', component: () => import('../pages/batch/BatchList.vue'), meta: { title: '批次管理', section: 'batches' } },
      { path: 'batches/:id(\\d+)', name: 'batch-detail', component: () => import('../pages/batch/BatchDetail.vue'), meta: { title: '批次详情', section: 'batches' } },
      { path: 'inspection', name: 'inspection', component: () => import('../pages/inspection/Inspection.vue'), meta: { title: 'AI 质检', section: 'inspection' } },
      { path: 'processing', name: 'processing', component: () => import('../pages/processing/Processing.vue'), meta: { title: '加工品控', section: 'processing' } },
      { path: 'coldchain', name: 'coldchain', component: () => import('../pages/coldchain/ColdChain.vue'), meta: { title: '冷链监测', section: 'coldchain' } },
      { path: 'traceability', name: 'traceability', component: () => import('../pages/trace/Traceability.vue'), meta: { title: '溯源与报告', section: 'traceability' } },
      { path: 'batches/:id(\\d+)/report', name: 'batch-report', component: () => import('../pages/report/BatchReport.vue'), meta: { title: '批次记录预览', section: 'traceability' } },
      { path: ':pathMatch(.*)*', name: 'not-found', component: () => import('../pages/NotFound.vue'), meta: { title: '页面不存在' } },
    ],
  },
  // Public page deliberately has no AdminLayout and does not fetch /batches.
  { path: '/trace/:batchCode', name: 'public-trace', component: () => import('../pages/trace/PublicTrace.vue'), meta: { title: '公开批次溯源' } },
]
const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.afterEach(to => { document.title = `${to.meta.title || '批次品控'} · 智检鲜达` })
export default router
