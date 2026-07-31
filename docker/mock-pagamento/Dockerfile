# syntax=docker/dockerfile:1

FROM golang:1.22-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go .
RUN CGO_ENABLED=0 go build -o /mock-pagamento .

FROM alpine:3.20 AS runtime
RUN apk add --no-cache wget && addgroup -S app && adduser -S app -G app
COPY --from=build /mock-pagamento /usr/local/bin/mock-pagamento
USER app
EXPOSE 8081
ENTRYPOINT ["mock-pagamento"]
