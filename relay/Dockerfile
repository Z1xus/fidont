FROM golang:1.27 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /relay .

FROM scratch
COPY --from=build /relay /relay
EXPOSE 8080
ENTRYPOINT ["/relay"]
