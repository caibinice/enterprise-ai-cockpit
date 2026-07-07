export const API_BASE = import.meta.env.VITE_API_BASE ?? '';

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const defaultHeaders: Record<string, string> = init.body instanceof FormData ? {} : { 'Content-Type': 'application/json' };
  const extraHeaders = (init.headers ?? {}) as Record<string, string>;
  const res = await fetch(`${API_BASE}/api${path}`, { ...init, headers: { ...defaultHeaders, ...extraHeaders } });
  if (!res.ok) throw new Error(await res.text());
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export type SseMessage = { event: string; data: string };

export async function streamChat(payload: unknown, onMessage: (message: SseMessage) => void) {
  const res = await fetch(`${API_BASE}/api/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  if (!res.ok || !res.body) throw new Error(await res.text());
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  while (true) {
    const { value, done } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split('\n\n');
    buffer = frames.pop() ?? '';
    for (const frame of frames) {
      let event = 'message';
      const data: string[] = [];
      for (const line of frame.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
      }
      onMessage({ event, data: data.join('\n') });
    }
  }
}
