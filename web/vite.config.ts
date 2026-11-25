import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig(() => {
  const proxy = {
    '/api': {
      target: process.env.GECKO_API_URL ?? 'http://127.0.0.1:8080',
      rewrite: (path: string) => '/gecko' + path,
    },
  };
  return {
    plugins: [react()],
    server: { proxy },
    preview: { proxy },
    test: { environment: 'jsdom', include: ['src/**/*.test.tsx'] },
  };
});
