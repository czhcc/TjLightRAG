export class ApiError extends Error {
  constructor(
    public status: number,
    public code?: string,
  ) {
    super(`HTTP ${status}`);
  }
}
export async function request<T>(
  url: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(url, options);
  if (!response.ok) {
    let code: string | undefined;
    try {
      const body = await response.json();
      if (
        typeof body.error === "string" &&
        /^retrieval_[a-z_]+$/.test(body.error)
      )
        code = body.error;
    } catch {
      /* Preserve status when an error has no JSON body. */
    }
    throw new ApiError(response.status, code);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export async function mutate<T>(
  url: string,
  body?: unknown,
  method = "POST",
  signal?: AbortSignal,
): Promise<T> {
  const csrf = await request<{ token: string }>("/api/auth/csrf", { signal });
  const multipart = body instanceof FormData;
  return request<T>(url, {
    method,
    signal,
    headers: multipart
      ? { "X-CSRF-TOKEN": csrf.token }
      : { "X-CSRF-TOKEN": csrf.token, "Content-Type": "application/json" },
    body:
      body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  });
}
export async function text(url: string, signal?: AbortSignal): Promise<string> {
  const response = await fetch(url, { signal });
  if (!response.ok) {
    let code: string | undefined;
    try {
      const body = await response.json();
      if (
        typeof body.error === "string" &&
        /^retrieval_[a-z_]+$/.test(body.error)
      )
        code = body.error;
    } catch {
      /* Preserve status when an error has no JSON body. */
    }
    throw new ApiError(response.status, code);
  }
  return response.text();
}
