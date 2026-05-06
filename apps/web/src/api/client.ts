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
  const fallback: ProblemDetail = {
    title: `HTTP ${res.status}`,
    status: res.status,
    detail: res.statusText,
  };
  if (ct.includes('application/problem+json') || ct.includes('application/json')) {
    try {
      const body = (await res.json()) as Record<string, unknown>;
      // Spring's default error JSON ({timestamp,status,error,path,message?}) lacks the RFC 9457
      // title/detail fields — without this normalization the UI would render an empty alert.
      const title =
        typeof body.title === 'string'
          ? body.title
          : typeof body.error === 'string'
            ? body.error
            : fallback.title;
      const detail =
        typeof body.detail === 'string'
          ? body.detail
          : typeof body.message === 'string'
            ? body.message
            : typeof body.path === 'string'
              ? `${title} at ${body.path}`
              : fallback.detail;
      return {
        type: typeof body.type === 'string' ? body.type : undefined,
        title,
        status: typeof body.status === 'number' ? body.status : res.status,
        detail,
      };
    } catch {
      // fall through to generic
    }
  }
  return fallback;
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
