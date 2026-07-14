export const API_BASE = (import.meta.env.VITE_API_BASE ?? '').replace(/\/+$/, '');

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

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (!(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const res = await fetch(`${API_BASE}/api${path}`, { ...init, headers });
  if (!res.ok) throw new Error(await responseError(res));
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export type SseMessage = { event: string; data: string };

export async function streamChat(payload: unknown, onMessage: (message: SseMessage) => void | Promise<void>) {
  const res = await fetch(`${API_BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
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
