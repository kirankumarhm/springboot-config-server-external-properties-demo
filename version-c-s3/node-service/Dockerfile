# node-service image.
#
#     cd node-service && docker build -t node-service .
#
# Alpine's packaged Node.js 22 is used rather than the node:22-alpine image; both run the same
# Node.js 22 line. The build stage installs only production dependencies, exactly as pinned in
# package-lock.json (npm ci), and the runtime stage carries no npm at all.
#
# Behind a TLS-inspecting corporate proxy (for example Zscaler), npm fails with "unable to get
# local issuer certificate". Pass the proxy's root certificate (PEM); it is trusted in the BUILD
# stage only and never reaches the runtime image:
#
#     docker build --build-arg EXTRA_CA_CERT="$(cat zscaler-root.pem)" -t node-service .
FROM alpine:3.21 AS build
ARG EXTRA_CA_CERT=""
RUN if [ -n "$EXTRA_CA_CERT" ]; then printf '%s\n' "$EXTRA_CA_CERT" >> /etc/ssl/certs/ca-certificates.crt; fi
RUN apk add --no-cache nodejs npm
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts

FROM alpine:3.21
RUN apk add --no-cache nodejs && adduser -S -D -H app
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY package.json ./
COPY src ./src
USER app
ENV NODE_ENV=production
EXPOSE 8104
CMD ["node", "src/index.js"]
