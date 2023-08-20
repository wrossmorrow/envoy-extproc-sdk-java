FROM golang:1.21-alpine

WORKDIR /app

COPY ./ ./
RUN go mod download && go build -o /echo
EXPOSE 8000
CMD [ "/echo" ]
