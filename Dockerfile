FROM eclipse-temurin:11
COPY build/libs/extproc.jar /extproc/extproc.jar
EXPOSE 50051
ENTRYPOINT [ "java" ]
CMD [ "-jar", "/extproc/extproc.jar" ]
