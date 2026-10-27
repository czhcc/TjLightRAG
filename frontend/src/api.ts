export class ApiError extends Error {
  constructor(public status: number) {
    super(`HTTP ${status}`);
  }
}
export async function request<T>(
  url: string,
  options: RequestInit = {},
): Promise<T> {
  const response = await fetch(url, options);
  if (!response.ok) throw new ApiError(response.status);
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export async function mutate<T>(
  url: string,
  body?: unknown,
  method = "POST",
): Promise<T> {
  const csrf = await request<{ token: string }>("/api/auth/csrf");
  const multipart = body instanceof FormData;
  return request<T>(url, {
    method,
    headers: multipart
      ? { "X-CSRF-TOKEN": csrf.token }
      : { "X-CSRF-TOKEN": csrf.token, "Content-Type": "application/json" },
    body:
      body === undefined ? undefined : multipart ? body : JSON.stringify(body),
  });
}
export async function text(url: string, signal?: AbortSignal): Promise<string> {
  const response = await fetch(url, { signal });
  if (!response.ok) throw new ApiError(response.status);
  return response.text();
}
