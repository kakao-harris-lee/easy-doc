FROM node:22-alpine AS dependencies
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci

FROM dependencies AS source
COPY . ./

FROM source AS check
CMD ["sh", "-c", "npm run check && npm run test -- --run && npm run build"]

FROM source AS build
ARG VITE_API_BASE_URL=/api
ARG VITE_EASYDOC_EXTRA_EASY_ENABLED=false
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}
ENV VITE_EASYDOC_EXTRA_EASY_ENABLED=${VITE_EASYDOC_EXTRA_EASY_ENABLED}
RUN npm run build

FROM nginx:alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
