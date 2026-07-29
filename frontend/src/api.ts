import {
  clearActionAuthorization,
  getActionAuthorization,
} from './actionAuth';

const viteEnv = import.meta.env || {};
export const API_BASE = (
  viteEnv.VITE_API_BASE ?? viteEnv.BASE_URL ?? '/smartCockpit/'
).replace(/\/+$/, '');

async function responseError(response: Response) {
  const body = await response.text();
  if (!body) return `${response.status} ${response.statusText}`;
  try {
    const parsed = JSON.parse(body) as {
      detail?: string;
      message?: string;
      error?: string;
    };
    return parsed.detail ?? parsed.message ?? parsed.error ?? body;
  } catch {
    return body;
  }
}

function needsActionAuth(init: RequestInit) {
  return ['POST', 'PUT', 'PATCH', 'DELETE']
    .includes((init.method ?? 'GET').toUpperCase());
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const protectedAction = needsActionAuth(init);
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const headers = new Headers(init.headers);
    if (!(init.body instanceof FormData) && !headers.has('Content-Type')) {
      headers.set('Content-Type', 'application/json');
    }
    if (protectedAction) {
      headers.set('Authorization', await getActionAuthorization());
    }
    const response = await fetch(`${API_BASE}/api${path}`, { ...init, headers });
    if (response.status === 401 && protectedAction && attempt === 0) {
      clearActionAuthorization();
      continue;
    }
    if (!response.ok) throw new Error(await responseError(response));
    if (response.status === 204) return undefined as T;
    return response.json() as Promise<T>;
  }
  throw new Error('操作验证已失效，请重试');
}

export type SseMessage = { event: string; data: string };

export async function streamChat(
  payload: unknown,
  onMessage: (message: SseMessage) => void | Promise<void>,
  signal?: AbortSignal,
) {
  const requestBody = JSON.stringify(payload);
  let response: Response | null = null;
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const authorization = await getActionAuthorization();
    response = await fetch(`${API_BASE}/api/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: authorization,
      },
      body: requestBody,
      signal,
    });
    if (response.status === 401 && attempt === 0) {
      clearActionAuthorization();
      continue;
    }
    break;
  }
  if (!response || !response.ok) {
    throw new Error(response ? await responseError(response) : '无法连接对话服务');
  }
  if (!response.body) throw new Error('浏览器不支持流式响应');

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  const dispatch = async (frame: string) => {
    if (!frame.trim()) return;
    let event = 'message';
    const data: string[] = [];
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) {
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
    for (const frame of frames) await dispatch(frame);
  }
}
