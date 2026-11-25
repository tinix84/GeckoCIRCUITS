import { defineConfig } from '@playwright/test';

const externalApi = process.env.GECKO_API_URL;
const apiUrl = externalApi ?? 'http://127.0.0.1:18081';

export default defineConfig({
  testDir: './e2e',
  use: { baseURL: 'http://127.0.0.1:4173' },
  webServer: [
    ...(externalApi ? [] : [{
      command: [
        'java -Xmx1g -jar ../src/modules/gecko-rest-api/target/gecko-rest-api-1.0.0.jar',
        '--server.address=127.0.0.1 --server.port=18081',
        '--gecko.api.auth-enabled=true --gecko.api.keys=gecko-web-test-key',
        '--gecko.api.jwt.enabled=true',
        '--gecko.api.jwt.secret=loopback-playwright-fixture-signing-secret',
      ].join(' '),
      url: apiUrl + '/gecko/api-docs',
      timeout: 90_000,
    }]),
    {
      command: 'npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      url: 'http://127.0.0.1:4173',
      env: { GECKO_API_URL: apiUrl },
    },
  ],
});
