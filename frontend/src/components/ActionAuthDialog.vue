<template>
  <Teleport to="body">
    <Transition name="auth-fade">
      <div
        v-if="open"
        class="auth-backdrop"
        role="presentation"
        @mousedown.self="cancel"
      >
        <form
          class="auth-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="auth-dialog-title"
          @submit.prevent="submit"
        >
          <div class="auth-symbol">
            <el-icon><Lock /></el-icon>
          </div>
          <span class="eyebrow">PROTECTED ACTION</span>
          <h2 id="auth-dialog-title">验证关键操作</h2>
          <p>
            对话调用、知识库写入、数据源和报告操作需要二次验证。
            令牌只保存在当前标签页，并在 30 分钟后自动失效。
          </p>
          <label class="auth-field">
            <span>操作密码</span>
            <input
              class="sr-only"
              name="username"
              autocomplete="username"
              value="enterprise-ai-cockpit"
              tabindex="-1"
              aria-hidden="true"
              readonly
            />
            <el-input
              ref="inputRef"
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="请输入操作密码"
              show-password
              @keyup.esc="cancel"
            />
          </label>
          <div v-if="error" class="auth-error">{{ error }}</div>
          <div class="auth-actions">
            <el-button :disabled="verifying" @click="cancel">取消</el-button>
            <el-button
              type="primary"
              native-type="submit"
              :loading="verifying"
              :disabled="!password"
            >
              验证并继续
            </el-button>
          </div>
        </form>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue';
import { Lock } from '@element-plus/icons-vue';
import type { InputInstance } from 'element-plus';
import { verifyActionPassword } from '../actionAuth';

type Pending = {
  resolve: (authorization: string) => void;
  reject: (error: Error) => void;
};

const open = ref(false);
const password = ref('');
const error = ref('');
const verifying = ref(false);
const inputRef = ref<InputInstance>();
let pending: Pending | null = null;

function requestAuthorization() {
  password.value = '';
  error.value = '';
  open.value = true;
  void nextTick(() => inputRef.value?.focus());
  return new Promise<string>((resolve, reject) => {
    pending = { resolve, reject };
  });
}

async function submit() {
  if (!password.value || verifying.value) return;
  verifying.value = true;
  error.value = '';
  try {
    const authorization = await verifyActionPassword(password.value);
    open.value = false;
    pending?.resolve(authorization);
    pending = null;
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '操作密码验证失败';
    void nextTick(() => inputRef.value?.focus());
  } finally {
    verifying.value = false;
  }
}

function cancel() {
  if (verifying.value) return;
  open.value = false;
  pending?.reject(new Error('已取消操作验证'));
  pending = null;
}

defineExpose({ requestAuthorization });
</script>
