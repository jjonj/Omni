FROM alpine:3.21
RUN apk add --no-cache iproute2 socat
COPY entrypoint.sh /usr/local/bin/flight-netem
RUN chmod +x /usr/local/bin/flight-netem
ENTRYPOINT ["/usr/local/bin/flight-netem"]
