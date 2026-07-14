import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { createRouter, createWebHistory } from 'vue-router';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import App from './App.vue';
import './style.css';

const routes = [
  { path: '/', component: App },
  { path: '/knowledge', component: App },
  { path: '/reports', component: App },
  { path: '/settings', component: App }
];

createApp(App)
  .use(createPinia())
  .use(createRouter({ history: createWebHistory(), routes }))
  .use(ElementPlus)
  .mount('#app');
