import { useAuthStore } from '../auth/useAuthStore';
import { env } from '../env';

/**
 * Typed fetch around the api service. Pulls the access token from the auth store on every call
 * so a silently-renewed session doesn't get left behind. Throws ApiError on non-2xx with the
 * RFC 9457 problem+json payload when the server provided one.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = useAuthStore.getState().accessToken;
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');

  const url = path.startsWith('http') ? path : `${env.VITE_API_BASE_URL}${path}`;
  const res = await fetch(url, { ...init, headers });

  if (!res.ok) {
    const detail = await readProblem(res);
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

async function readProblem(res: Response): Promise<ProblemDetail> {
  const ct = res.headers.get('Content-Type') ?? '';
  if (ct.includes('application/problem+json') || ct.includes('application/json')) {
    try {
      return (await res.json()) as ProblemDetail;
    } catch {
      // fall through to generic
    }
  }
  return { title: `HTTP ${res.status}`, status: res.status, detail: res.statusText };
}

export interface ProblemDetail {
  type?: string;
  title: string;
  status: number;
  detail?: string;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly problem: ProblemDetail,
  ) {
    super(problem.detail ?? problem.title);
    this.name = 'ApiError';
  }
}

export function buildQuery(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;
    search.set(key, String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : '';
}
