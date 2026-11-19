# syntax=docker/dockerfile:1

FROM eclipse-temurin:17-jdk-jammy AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace

COPY pom.xml .
COPY common-events/pom.xml common-events/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY print-service/pom.xml print-service/pom.xml
COPY common-events/src common-events/src
COPY payment-service/src payment-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -q -pl payment-service -am -DskipTests package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/payment-service/target/payment-service-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8082
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
