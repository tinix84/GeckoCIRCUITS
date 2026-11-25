import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { App } from './App';
import { SessionProvider } from './session';
import { publicApi } from './api/client';

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

test('shows sign-in before a protected route', () => {
  render(<MemoryRouter initialEntries={['/circuits']}><SessionProvider><App /></SessionProvider></MemoryRouter>);
  expect(screen.getByRole('heading', { name: 'Sign in' })).toBeTruthy();
  expect(screen.queryByRole('heading', { name: 'Circuit library' })).toBeNull();
});

test('reports a rejected key in the sign-in form', async () => {
  vi.spyOn(publicApi, 'POST').mockResolvedValue({ error: {}, response: new Response(null, { status: 401 }) });
  render(<MemoryRouter><SessionProvider><App /></SessionProvider></MemoryRouter>);
  fireEvent.change(screen.getByLabelText(/API key/), { target: { value: 'rejected' } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
  expect((await screen.findByRole('alert')).textContent).toContain('HTTP 401');
});
