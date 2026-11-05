import { createRouter, createWebHistory } from 'vue-router';

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/screen' },
    { path: '/screen', component: () => import('../views/ScreenView.vue') },
    { path: '/datasources', component: () => import('../views/DatasourcesView.vue') },
    { path: '/tasks', component: () => import('../views/SyncTasksView.vue') },
    { path: '/quality', component: () => import('../views/QualityView.vue') },
    { path: '/alerts', component: () => import('../views/AlertsView.vue') },
  ],
});
