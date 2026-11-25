import createClient from 'openapi-fetch';
import type { paths } from './schema';

const baseUrl = import.meta.env.VITE_GECKO_API_URL ?? '';
export const publicApi = createClient<paths>({ baseUrl });
export const api = createClient<paths>({ baseUrl });

let token: string | undefined;
export function setAccessToken(value?: string) {
  token = value;
}

api.use({
  onRequest({ request }) {
    if (token) request.headers.set('Authorization', 'Bearer ' + token);
  },
});

export function requireData<T>(result: { data?: T; response: Response }, message: string): T {
  if (!result.response.ok || result.data === undefined) {
    throw new Error(message + ' (HTTP ' + result.response.status + ').');
  }
  return result.data;
}
