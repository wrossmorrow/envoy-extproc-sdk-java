FROM eclipse-temurin:11
WORKDIR /extproc
COPY build/libs/extproc-*.jar ./extproc.jar
EXPOSE 50051
ENTRYPOINT [ "java" ]
CMD [ "-jar", "/extproc/extproc.jar" ]
