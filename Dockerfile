ARG DOCKER_BASE_JAVA_VERSION_TAG=17
ARG GRPC_HEALTH_PROBE_VERSION=v0.4.39
ARG RELEASE_VERSION=0.5.0-SNAPSHOT

FROM eclipse-temurin:${DOCKER_BASE_JAVA_VERSION_TAG}-jdk AS builder

WORKDIR /opt/extproc
COPY build.gradle ./
COPY settings.gradle ./
COPY gradle/ ./gradle/
COPY --chmod=0755 gradlew ./
RUN ./gradlew --no-daemon dependencies

COPY src/ ./src/
ARG RELEASE_VERSION
RUN ./gradlew --no-daemon jar -PreleaseVersion="${RELEASE_VERSION}"

FROM eclipse-temurin:${DOCKER_BASE_JAVA_VERSION_TAG}-jre
WORKDIR /extproc
COPY --from=builder /opt/extproc/build/libs/extproc-*.jar /extproc/extproc.jar
ARG GRPC_HEALTH_PROBE_VERSION
ARG TARGETARCH
RUN curl -fsSL "https://github.com/grpc-ecosystem/grpc-health-probe/releases/download/${GRPC_HEALTH_PROBE_VERSION}/grpc_health_probe-linux-${TARGETARCH}" \
    -o /grpc-health-probe && \
    chmod +x /grpc-health-probe

EXPOSE 50051 9090
ENTRYPOINT ["java"]
CMD ["-jar", "/extproc/extproc.jar"]
