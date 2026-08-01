<template>
  <div ref="root" class="chart-view" role="img" aria-label="动态分析图"></div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { getInstanceByDom, init as initChart } from 'echarts/core';
import type { ECharts, EChartsCoreOption } from 'echarts/core';

const props = defineProps<{ option: EChartsCoreOption }>();
const root = ref<HTMLDivElement>();
let instance: ECharts | undefined;
let observer: ResizeObserver | undefined;

async function render() {
  await nextTick();
  if (!root.value) return;
  instance ??= getInstanceByDom(root.value) ?? initChart(root.value);
  instance.setOption(props.option, true);
  instance.resize();
}

watch(() => props.option, () => { void render(); }, { deep: true });

onMounted(() => {
  observer = new ResizeObserver(() => instance?.resize());
  if (root.value) observer.observe(root.value);
  void render();
});

onBeforeUnmount(() => {
  observer?.disconnect();
  instance?.dispose();
  instance = undefined;
});
</script>
