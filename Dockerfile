FROM eclipse-temurin:11

SHELL ["/bin/bash", "-c"]

# https://github.com/grpc-ecosystem/grpc-health-probe/#example-grpc-health-checking-on-kubernetes
RUN GRPC_HEALTH_PROBE_VER=v0.3.1 \
    && GRPC_HEALTH_PROBE_URL=https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VER}/grpc_health_probe-linux-amd64 \
    && curl ${GRPC_HEALTH_PROBE_URL} -L -s -o /bin/grpc_health_probe \
    && chmod +x /bin/grpc_health_probe

WORKDIR /app

ARG EXAMPLE=noop

COPY build/libs/extproc.jar /extproc/extproc.jar

EXPOSE 50051

ENTRYPOINT [ "java" ]
CMD [ "-jar", "/extproc/extproc.jar" ]
