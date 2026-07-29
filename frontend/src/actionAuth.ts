const viteEnv = import.meta.env || {};
const API_BASE = (
  viteEnv.VITE_API_BASE ?? viteEnv.BASE_URL ?? '/smartCockpit/'
).replace(/\/+$/, '');
const TOKEN_KEY = 'cockpit-action-token';

type TokenRequester = () => Promise<string>;

let requester: TokenRequester | null = null;
let pendingAuthorization: Promise<string> | null = null;

function storedAuthorization(): string | null {
  const token = sessionStorage.getItem(TOKEN_KEY);
  if (!token) return null;
  const expiresAt = Number(token.split('.', 1)[0]);
  if (!Number.isFinite(expiresAt) || expiresAt * 1000 <= Date.now()) {
    sessionStorage.removeItem(TOKEN_KEY);
    return null;
  }
  return `Bearer ${token}`;
}

export function setActionTokenRequester(nextRequester: TokenRequester | null) {
  requester = nextRequester;
}

export function storeActionToken(token: string) {
  sessionStorage.setItem(TOKEN_KEY, token);
  return `Bearer ${token}`;
}

export function clearActionAuthorization() {
  sessionStorage.removeItem(TOKEN_KEY);
}

export async function getActionAuthorization(): Promise<string> {
  const existing = storedAuthorization();
  if (existing) return existing;
  if (!requester) throw new Error('操作验证界面尚未就绪');
  if (pendingAuthorization) return pendingAuthorization;
  pendingAuthorization = requester().finally(() => {
    pendingAuthorization = null;
  });
  return pendingAuthorization;
}

export async function verifyActionPassword(password: string) {
  const response = await fetch(`${API_BASE}/api/action-auth/verify`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ password }),
  });
  const body = await response.json().catch(() => ({})) as {
    token?: string;
    message?: string;
    error?: string;
  };
  if (!response.ok || !body.token) {
    throw new Error(body.message || body.error || '操作密码验证失败');
  }
  return storeActionToken(body.token);
}
