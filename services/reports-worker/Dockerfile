ARG BUILDPLATFORM
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:25-jdk-noble AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY common common
COPY services services
COPY loadgen loadgen
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -pl services/reports-worker -am -DskipTests package

FROM docker.io/library/eclipse-temurin:25-jre-noble
ENV HOME=/tmp LANG=C.UTF-8 TZ=UTC
WORKDIR /app
COPY --from=build --chown=1001:0 /src/services/reports-worker/target/app.jar /app/app.jar
COPY --chown=1001:0 container/entrypoint-jar.sh /app/entrypoint.sh
USER 1001:0
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
