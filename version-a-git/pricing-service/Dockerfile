# Runtime-only image for pricing-service.
#
# The jar is built by Maven on the host first, rather than compiling inside the image, because a
# build stage would re-download the whole Boot 4 / Cloud 2025.1.3 dependency tree on every change
# instead of reusing the host ~/.m2 cache.
#
# The build context is this service's own directory, so this image can be built without reference
# to any sibling service:
#
#     cd pricing-service && mvn package && docker build -t pricing-service .
FROM eclipse-temurin:21-jre-alpine

ARG JAR_VERSION=1.0.0

RUN addgroup -S app && adduser -S -G app app

WORKDIR /app
COPY target/pricing-service-${JAR_VERSION}.jar /app/application.jar
RUN chown -R app:app /app
USER app

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["java", "-jar", "/app/application.jar"]
