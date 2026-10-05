ARG REPOSITORY_BASE=docker.io/library
FROM ${REPOSITORY_BASE}/eclipse-temurin:21-jre-noble AS build
WORKDIR /app
ARG JAR_FILE=*.jar
COPY ./target/${JAR_FILE} ./app.jar
RUN java -Djarmode=tools -jar ./app.jar extract --layers --launcher --destination out

FROM ${REPOSITORY_BASE}/eclipse-temurin:21-jre-noble
WORKDIR /application
COPY --from=build /app/out/dependencies ./
COPY --from=build /app/out/spring-boot-loader ./
COPY --from=build /app/out/snapshot-dependencies ./
COPY --from=build /app/out/application ./
CMD ["java", "org.springframework.boot.loader.launch.JarLauncher"]
