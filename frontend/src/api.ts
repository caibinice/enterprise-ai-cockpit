import { ElMessage, ElMessageBox } from 'element-plus';

const viteEnv = import.meta.env || {};
export const API_BASE = (
  viteEnv.VITE_API_BASE ?? viteEnv.BASE_URL ?? '/smartCockpit/'
).replace(/\/+$/, '');
const ACTION_TOKEN_KEY = 'cockpit-action-token';
let pendingAuthorization: Promise<string> | null = null;

async function responseError(res: Response) {
  const body = await res.text();
  if (!body) return `${res.status} ${res.statusText}`;
  try {
    const parsed = JSON.parse(body) as { message?: string; error?: string };
    return parsed.message ?? parsed.error ?? body;
  } catch {
    return body;
  }
}

function storedAuthorization() {
  const token = sessionStorage.getItem(ACTION_TOKEN_KEY);
  if (!token) return null;
  const expiresAt = Number(token.split('.', 1)[0]);
  if (!Number.isFinite(expiresAt) || expiresAt * 1000 <= Date.now()) {
    sessionStorage.removeItem(ACTION_TOKEN_KEY);
    return null;
  }
  return `Bearer ${token}`;
}

function requestActionAuthorization(): Promise<string> {
  const existing = storedAuthorization();
  if (existing) return Promise.resolve(existing);
  if (pendingAuthorization) return pendingAuthorization;
  pendingAuthorization = (async () => {
    let value: string;
    try {
      const result = await ElMessageBox.prompt(
        '聊天、知识导入、数据源测试和报告生成会调用后端资源，请输入操作密码继续。',
        '验证敏感操作',
        {
          confirmButtonText: '验证并继续',
          cancelButtonText: '取消',
          inputType: 'password',
          inputPlaceholder: '操作密码',
          inputValidator: (input) => Boolean(input) || '请输入操作密码',
          closeOnClickModal: false,
        },
      );
      value = result.value;
    } catch {
      throw new Error('已取消操作验证');
    }
    const response = await fetch(`${API_BASE}/api/action-auth/verify`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password: value }),
    });
    if (!response.ok) {
      const message = await responseError(response);
      ElMessage.error(message);
      throw new Error(message);
    }
    const body = await response.json() as { token?: string };
    if (!body.token) throw new Error('后端未返回操作令牌');
    sessionStorage.setItem(ACTION_TOKEN_KEY, body.token);
    return `Bearer ${body.token}`;
  })().finally(() => {
    pendingAuthorization = null;
  });
  return pendingAuthorization;
}

function needsActionAuth(init: RequestInit) {
  return ['POST', 'PUT', 'PATCH', 'DELETE'].includes((init.method ?? 'GET').toUpperCase());
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (!(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const protectedAction = needsActionAuth(init);
  if (protectedAction) headers.set('Authorization', await requestActionAuthorization());
  const res = await fetch(`${API_BASE}/api${path}`, { ...init, headers });
  if (res.status === 401 && protectedAction) sessionStorage.removeItem(ACTION_TOKEN_KEY);
  if (!res.ok) throw new Error(await responseError(res));
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export type SseMessage = { event: string; data: string };

export async function streamChat(payload: unknown, onMessage: (message: SseMessage) => void | Promise<void>) {
  const authorization = await requestActionAuthorization();
  const res = await fetch(`${API_BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: authorization },
    body: JSON.stringify(payload)
  });
  if (res.status === 401) sessionStorage.removeItem(ACTION_TOKEN_KEY);
  if (!res.ok) throw new Error(await responseError(res));
  if (!res.body) throw new Error('Streaming response body is unavailable');
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = async (frame: string) => {
    if (!frame.trim()) return;
    let event = 'message';
    const data: string[] = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) {
        // SSE removes at most one optional space after `data:`. Preserve token whitespace.
        let value = line.slice(5);
        if (value.startsWith(' ')) value = value.slice(1);
        data.push(value);
      }
    }
    await onMessage({ event, data: data.join('\n') });
  };

  while (true) {
    const { value, done } = await reader.read();
    if (done) {
      buffer += decoder.decode();
      if (buffer.trim()) await dispatch(buffer);
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() ?? '';
    for (const frame of frames) {
      await dispatch(frame);
    }
  }
}
