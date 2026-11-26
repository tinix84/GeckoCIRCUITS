import { createContext, useContext, useEffect, useRef, useState } from 'react';
import type { PropsWithChildren } from 'react';
import { api, publicApi, requireData, setAccessToken, usesCurrentAccessToken } from './api/client';
import type { components } from './api/schema';

type Session = { active: boolean; signIn: (key: string) => Promise<void>; signOut: () => void };
const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: PropsWithChildren) {
  const [active, setActive] = useState(false);
  const timer = useRef<number>(undefined);
  const generation = useRef(0);

  function signOut() {
    generation.current += 1;
    window.clearTimeout(timer.current);
    setAccessToken();
    setActive(false);
  }

  function accept(pair: components['schemas']['TokenPair'], request: number) {
    if (generation.current !== request) return;
    const { accessToken, refreshToken, expiresIn } = pair;
    if (!accessToken || !refreshToken || !expiresIn) throw new Error('Incomplete token response.');
    setAccessToken(accessToken);
    setActive(true);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(async () => {
      try {
        const result = await publicApi.POST('/api/v1/auth/refresh', { body: { refreshToken } });
        accept(requireData(result, 'Could not refresh the session'), request);
      } catch {
        if (generation.current === request) signOut();
      }
    }, Math.min(expiresIn * 800, 2_147_483_647));
  }

  async function signIn(key: string) {
    const request = ++generation.current;
    const result = await publicApi.POST('/api/v1/auth/token', { params: { header: { 'X-API-Key': key } } });
    accept(requireData(result, 'Could not sign in'), request);
  }

  useEffect(() => {
    const middleware = { onResponse({ request, response }: { request: Request; response: Response }) {
      if (response.status === 401 && usesCurrentAccessToken(request)) signOut();
    } };
    api.use(middleware);
    return () => {
      api.eject(middleware);
      generation.current += 1;
      window.clearTimeout(timer.current);
      setAccessToken();
    };
  }, []);

  return <SessionContext.Provider value={{ active, signIn, signOut }}>{children}</SessionContext.Provider>;
}

export function useSession() {
  const context = useContext(SessionContext);
  if (!context) throw new Error('The session provider is required.');
  return context;
}
