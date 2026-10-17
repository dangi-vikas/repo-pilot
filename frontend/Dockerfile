# =============================================================================
# RepoPilot frontend image: build the SPA, serve it as static files, and reverse
# proxy /api to the backend so the browser sees a single origin.
#
# A single origin matters here: it means no CORS preflight, the bearer token is
# never sent to a third origin, and the production bundle needs no API base URL
# baked in at build time.
# =============================================================================

# syntax=docker/dockerfile:1

FROM node:20-alpine AS build

WORKDIR /build

# Lockfile first: `npm ci` is reproducible and only re-runs when dependencies
# actually change.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json tsconfig.node.json vite.config.ts index.html ./
COPY src/ src/
# The production build runs `tsc --noEmit` before Vite (see package.json), so a
# type error fails the image build rather than shipping.
RUN npm run build

# -----------------------------------------------------------------------------

FROM nginx:1.27-alpine AS runtime

COPY --from=build /build/dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
