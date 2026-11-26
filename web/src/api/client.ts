import createClient from 'openapi-fetch';
import type { paths } from './schema';

const baseUrl = import.meta.env.VITE_GECKO_API_URL ?? '';
export const publicApi = createClient<paths>({ baseUrl });
export const api = createClient<paths>({ baseUrl });

let token: string | undefined;
export function setAccessToken(value?: string) {
  token = value;
}

export function usesCurrentAccessToken(request: Request) {
  return token !== undefined && request.headers.get('Authorization') === 'Bearer ' + token;
}

api.use({
  onRequest({ request }) {
    if (token) request.headers.set('Authorization', 'Bearer ' + token);
  },
});

export function requireData<T>(result: { data?: T; response: Response; error?: unknown }, message: string): T {
  if (!result.response.ok || result.data === undefined) {
    const { error } = result;
    const detail = typeof error === 'object' && error && 'message' in error && typeof error.message === 'string'
      ? ' ' + error.message : '';
    throw new Error(message + ' (HTTP ' + result.response.status + ').' + detail);
  }
  return result.data;
}
