<script setup lang="ts">
import {computed} from 'vue';
const props=defineProps<{text:string}>();
// Render a small, text-only Markdown subset. Model text never becomes HTML.
const lines=computed(()=>props.text.split(/\r?\n/).map((line,index)=>{
  const heading=line.match(/^#{1,3}\s+(.+)/),bullet=line.match(/^\s*[-*]\s+(.+)/);
  const value=heading?.[1]??bullet?.[1]??line;
  const parts=(value??'').split(/(\*\*[^*]+\*\*|`[^`]+`|\[KB\d+\])/g).filter(Boolean).map(text=>({
    tag:text.startsWith('**')?'strong':text.startsWith('`')?'code':/^\[KB\d+\]$/.test(text)?'mark':'span',
    text:text.startsWith('**')?text.slice(2,-2):text.startsWith('`')?text.slice(1,-1):text
  }));
  return {index,tag:heading?'h3':'p',bullet:Boolean(bullet),blank:!line.trim(),parts};
}));
</script>
<template>
  <div class="rich-answer">
    <template v-for="line in lines" :key="line.index">
      <div v-if="line.blank" class="answer-gap" aria-hidden="true"></div>
      <component v-else :is="line.tag" :class="{'answer-bullet':line.bullet}">
        <component v-for="(part,index) in line.parts" :key="index" :is="part.tag">{{part.text}}</component>
      </component>
    </template>
  </div>
</template>
