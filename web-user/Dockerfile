# syntax=docker/dockerfile:1.7
FROM node:22-bookworm-slim AS build
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=secret,id=proxyca,target=/tmp/proxy-ca.crt,required=false \
    if [ -s /tmp/proxy-ca.crt ]; then NODE_EXTRA_CA_CERTS=/tmp/proxy-ca.crt npm ci --ignore-scripts; else npm ci --ignore-scripts; fi
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=10 \
  CMD wget -q -Y off -O /dev/null http://127.0.0.1/ || exit 1
