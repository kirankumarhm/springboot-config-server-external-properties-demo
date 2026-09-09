# Runtime-only image. The jar is built by Maven on the host first, rather than compiling inside
# the image, because a build stage would re-download the whole Boot 4 / Cloud 2025.1.3 dependency
# tree on every change instead of reusing the host ~/.m2 cache.
FROM eclipse-temurin:21-jre-alpine

ARG MODULE
ARG JAR_VERSION=1.0.0

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY ${MODULE}/target/${MODULE}-${JAR_VERSION}.jar /app/application.jar
RUN chown -R app:app /app
USER app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
