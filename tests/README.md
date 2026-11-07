# Test layers

## Compose environment

The integration checks run through the same Nginx gateway used by the deployed
frontends. Copy `.env.example` for local development, then start the stack:

```bash
cp .env.example .env
PROXY_HOST_IP="$(getent hosts proxy 2>/dev/null | awk '{print $1}')" \
  docker compose --env-file .env -f deploy/docker-compose.yml up -d --build --wait
```

Set `API_BASE_URL=http://localhost:8088/api/v1` for the smoke scripts. The
production-like frontend URLs are `USER_WEB_URL=http://localhost:8088/` and
`ADMIN_WEB_URL=http://localhost:8088/admin/`.

The backend unit suite is run with the Docker Maven environment because the host image does not ship Maven:

```bash
docker run --rm \
  --add-host=proxy:"$(getent hosts proxy | awk '{print $1}')" \
  -e HTTP_PROXY -e HTTPS_PROXY -e NO_PROXY \
  -e APP_ENV=test \
  -e DB_URL=jdbc:mysql://127.0.0.1:3306/homestay \
  -e DB_USERNAME=homestay -e DB_PASSWORD=test-db-password \
  -e REDIS_HOST=127.0.0.1 -e REDIS_PASSWORD=test-redis-password \
  -e GUEST_ID_ENCRYPTION_KEY=0123456789abcdef0123456789abcdef \
  -v "$CODEX_PROXY_CERT:/tmp/proxy-ca.crt:ro" \
  -v "$PWD/backend:/app" -w /app \
  maven:3.9-eclipse-temurin-21 \
  sh -lc 'keytool -importcert -noprompt -trustcacerts -alias environment-proxy -file /tmp/proxy-ca.crt -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit >/dev/null; printf "%s" "<settings><proxies><proxy><id>environment</id><active>true</active><protocol>http</protocol><host>proxy</host><port>8080</port></proxy></proxies></settings>" >/tmp/settings.xml; mvn -s /tmp/settings.xml test'
```

With the backend running, interface regression tests cover CORS, authentication, order isolation, guest masking, payment, cancellation, and RBAC:

```bash
node tests/api-smoke.mjs
```

运营后台接口回归覆盖民宿资料、房型图片/设施、房务状态、人工订单、收款、分房、库存日历和审计。管理员凭据必须显式传入，不使用默认密码：

```bash
E2E_ADMIN_USERNAME=admin E2E_ADMIN_PASSWORD='<development password>' node tests/operations-smoke.mjs
```

For browser end-to-end coverage through the gateway, run:

```bash
API_BASE_URL=http://localhost:8088/api/v1 \
USER_WEB_URL=http://localhost:8088/ \
ADMIN_WEB_URL=http://localhost:8088/admin/ \
E2E_ADMIN_USERNAME=admin E2E_ADMIN_PASSWORD='<development password>' \
node tests/e2e.mjs
```

`PLAYWRIGHT_MODULE` and `CHROMIUM_PATH` can override the managed runtime defaults.

The GitHub Actions workflow reproduces this sequence in a clean environment;
failures in Maven, either frontend build, Docker health checks, API smoke, or
Playwright E2E fail the workflow.
