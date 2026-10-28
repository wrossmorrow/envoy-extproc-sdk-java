ARG DOCKER_BASE_JAVA_VERSION_TAG=17
FROM eclipse-temurin:${DOCKER_BASE_JAVA_VERSION_TAG}
WORKDIR /extproc
COPY build/libs/extproc-*.jar ./extproc.jar
EXPOSE 50051
ENTRYPOINT [ "java" ]
CMD [ "-jar", "/extproc/extproc.jar" ]
