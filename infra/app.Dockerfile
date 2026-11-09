# Only verified host build artifacts enter the runtime image; no dependency downloads.
ARG JAVA_IMAGE=eclipse-temurin:21-jre-alpine@sha256:51ab5e3302e7141ce665ca3ea85e8b5cd648eafbc3c0c90dd79d6537684e4555
FROM ${JAVA_IMAGE}
WORKDIR /app
COPY --chown=10001:10001 backend/target/ticket-platform-0.1.0.jar /app/ticket.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/ticket.jar"]
