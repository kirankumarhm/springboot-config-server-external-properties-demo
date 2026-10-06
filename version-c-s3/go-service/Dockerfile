# go-service image.
#
#     cd go-service && docker build -t go-service .
#
# Behind a TLS-inspecting corporate proxy (for example Zscaler), module downloads fail with
# "x509: certificate signed by unknown authority". Pass the proxy's root certificate (PEM) and it
# is trusted in the BUILD stage only - it never reaches the runtime image:
#
#     docker build --build-arg EXTRA_CA_CERT="$(cat zscaler-root.pem)" -t go-service .
FROM golang:1.23-alpine AS build
ARG EXTRA_CA_CERT=""
RUN if [ -n "$EXTRA_CA_CERT" ]; then printf '%s\n' "$EXTRA_CA_CERT" >> /etc/ssl/certs/ca-certificates.crt; fi
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY cmd ./cmd
COPY internal ./internal
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /go-service ./cmd/go-service

# Alpine keeps busybox wget for the Compose health check; the binary runs as a non-root user.
FROM alpine:3.21
COPY --from=build /go-service /usr/local/bin/go-service
USER nobody
EXPOSE 8105
ENTRYPOINT ["go-service"]
