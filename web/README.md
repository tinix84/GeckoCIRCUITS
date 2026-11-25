# GeckoCIRCUITS Web

Use Node.js 24 and Java 21. From the repository root, build the REST API JAR.

```sh
mvn -f pom-reactor.xml -pl src/modules/gecko-rest-api -am package -DskipTests
```

Start the REST API with API-key authentication and JWT enabled. Set the API keys and signing secret in the server environment. Use a signing secret of at least 32 bytes.

```sh
GECKO_API_AUTH_ENABLED=true GECKO_API_JWT_ENABLED=true \
  java -jar src/modules/gecko-rest-api/target/gecko-rest-api-1.0.0.jar
```

From `web/`, install the dependencies and start the development server. Open the URL that Vite prints. Sign in with a configured API key.

```sh
npm ci
npm run dev
```

Vite forwards `/api` requests to `http://127.0.0.1:8080/gecko/api`. Use `GECKO_API_URL` to select a different server origin. A production host must forward `/api` to the REST API and serve `index.html` for browser routes.

Run the browser tests with the built REST API JAR. Playwright starts a loopback REST API and a preview server.

```sh
npm run build
npm test
npx playwright install chromium
npx playwright test
```
