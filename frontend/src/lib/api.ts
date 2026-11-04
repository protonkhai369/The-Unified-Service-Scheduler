export type InputError = { field: string; message: string };

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly detail?: string,
    readonly requestId?: string,
    readonly errors: InputError[] = [],
  ) {
    super(detail ?? code);
    this.name = 'ApiError';
  }
}

export type ApiClient = {
  get<T>(path: string): Promise<T>;
  post<T>(path: string, body?: unknown, options?: { idempotencyKey?: string }): Promise<T>;
};

type ApiClientOptions = {
  getAccessToken: () => Promise<string | null>;
  onUnauthorized: () => void | Promise<void>;
  baseUrl?: string;
};

export function createApiClient({ getAccessToken, onUnauthorized, baseUrl }: ApiClientOptions): ApiClient {
  async function request<T>(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<T> {
    const headers = new Headers({ Accept: 'application/json' });
    const token = await getAccessToken();
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    if (body !== undefined) {
      headers.set('Content-Type', 'application/json');
    }
    if (idempotencyKey) {
      headers.set('Idempotency-Key', idempotencyKey);
    }

    let response: Response;
    try {
      response = await fetch(new URL(path, baseUrl ?? window.location.origin), {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      throw new ApiError(0, 'NETWORK_ERROR', 'The server could not be reached.');
    }

    if (response.ok) {
      return (response.status === 204 ? undefined : await response.json()) as T;
    }
    if (response.status === 401) {
      await onUnauthorized();
    }
    throw await toApiError(response);
  }

  return {
    get: (path) => request('GET', path),
    post: (path, body, options) => request('POST', path, body, options?.idempotencyKey),
  };
}

async function toApiError(response: Response): Promise<ApiError> {
  const headerRequestId = response.headers.get('X-Request-Id') ?? undefined;
  if (response.headers.get('Content-Type')?.includes('json')) {
    try {
      const problem = await response.json();
      if (typeof problem?.code === 'string') {
        return new ApiError(response.status, problem.code, problem.detail, problem.requestId ?? headerRequestId, problem.errors ?? []);
      }
    } catch {
      // Falls through to the generic error below.
    }
  }
  const code = response.status >= 500 ? 'INTERNAL_ERROR' : 'REQUEST_REJECTED';
  return new ApiError(response.status, code, undefined, headerRequestId);
}
