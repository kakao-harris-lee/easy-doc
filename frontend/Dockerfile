# React 프로젝트 이미지. build context는 frontend/이며 백엔드 파일을 읽지 않는다.

FROM node:22-alpine AS dependencies
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS source
COPY . ./

# CI에서 `docker compose ... run frontend-check`가 사용하는 개발 의존성 포함 단계.
FROM source AS check
CMD ["sh", "-c", "npm run check && npm run test -- --run && npm run build"]

FROM source AS build
ARG VITE_API_BASE_URL=/api
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
RUN npm run build

FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
