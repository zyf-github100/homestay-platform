# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} NO_PROXY=${NO_PROXY}
WORKDIR /app
COPY pom.xml .
RUN --mount=type=secret,id=proxyca,target=/tmp/proxy-ca.crt,required=false \
    set -eux; \
    settings=/tmp/maven-settings.xml; \
    if [ -s /tmp/proxy-ca.crt ]; then \
      keytool -importcert -noprompt -trustcacerts -alias environment-proxy \
        -file /tmp/proxy-ca.crt -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit; \
    fi; \
    if [ -n "${HTTPS_PROXY}" ]; then \
      proxy="${HTTPS_PROXY#*://}"; \
      proxy="${proxy##*@}"; \
      proxy_host="${proxy%:*}"; \
      proxy_port="${proxy##*:}"; \
      printf '<settings><proxies><proxy><id>environment</id><active>true</active><protocol>http</protocol><host>%s</host><port>%s</port></proxy></proxies></settings>' "$proxy_host" "$proxy_port" > "$settings"; \
      test -s "$settings"; \
    fi
COPY src src
RUN if [ -f /tmp/maven-settings.xml ]; then mvn -s /tmp/maven-settings.xml -q -DskipTests package; else mvn -q -DskipTests package; fi

FROM eclipse-temurin:21-jre
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
  CMD curl --fail --silent http://127.0.0.1:8080/api/v1/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
