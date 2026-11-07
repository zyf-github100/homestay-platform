# Test layers

The backend unit suite is run with the Docker Maven environment because the host image does not ship Maven:

```bash
PROXY_IP="$(getent hosts proxy | awk 'NR == 1 { print $1 }')"
docker run --rm \
  --add-host="proxy:${PROXY_IP}" \
  -e HTTP_PROXY -e HTTPS_PROXY -e NO_PROXY \
  -v "$CODEX_PROXY_CERT:/tmp/proxy-ca.crt:ro" \
  -v "$PWD/backend:/app" -w /app \
  maven:3.9-eclipse-temurin-21 \
  sh -lc 'keytool -importcert -noprompt -trustcacerts -alias environment-proxy -file /tmp/proxy-ca.crt -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit >/dev/null; printf "%s" "<settings><proxies><proxy><id>environment</id><active>true</active><protocol>https</protocol><host>proxy</host><port>8080</port></proxy></proxies></settings>" >/tmp/settings.xml; mvn -s /tmp/settings.xml test'
```

With the backend running, interface regression tests cover CORS, authentication, order isolation, guest masking, payment, cancellation, and RBAC:

```bash
node tests/api-smoke.mjs
```

运营后台接口回归覆盖民宿资料、房型图片/设施、房务状态、人工订单、收款、分房、库存日历和审计：

```bash
E2E_ADMIN_USERNAME=admin E2E_ADMIN_PASSWORD=admin123 node tests/operations-smoke.mjs
```

For browser end-to-end coverage, start the backend and both Vite dev servers first, then run:

```bash
E2E_ADMIN_USERNAME=staff E2E_ADMIN_PASSWORD=Pass1234 node tests/e2e.mjs
```

`PLAYWRIGHT_MODULE` and `CHROMIUM_PATH` can override the managed runtime defaults.
