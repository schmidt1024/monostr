FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/monostr-watcher ./cmd/monostr-watcher
# distroless has no shell: create the data dir here and hand it to the
# nonroot user (uid 65532) so a fresh named volume inherits the ownership.
RUN mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/monostr-watcher /monostr-watcher
COPY --from=build --chown=65532:65532 /out/data /data
VOLUME /data
ENTRYPOINT ["/monostr-watcher"]
