# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ENV HTTP_PROXY=${HTTP_PROXY} HTTPS_PROXY=${HTTPS_PROXY} NO_PROXY=${NO_PROXY}
WORKDIR /app
COPY pom.xml .
RUN --mount=type=secret,id=proxyca,target=/tmp/proxy-ca.crt \
    keytool -importcert -noprompt -trustcacerts -alias environment-proxy \
      -file /tmp/proxy-ca.crt -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit \
    && proxy="${HTTPS_PROXY#*://}" \
    && proxy="${proxy##*@}" \
    && proxy_host="${proxy%:*}" \
    && proxy_port="${proxy##*:}" \
    && printf '<settings><proxies><proxy><id>environment</id><active>true</active><protocol>https</protocol><host>%s</host><port>%s</port></proxy></proxies></settings>' "$proxy_host" "$proxy_port" > /tmp/maven-settings.xml \
    && mvn -s /tmp/maven-settings.xml -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -s /tmp/maven-settings.xml -q -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
