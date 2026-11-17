# Refresh a prebuilt app runtime without pulling Node, Maven, or a JRE image.
# Build with the Maven target directory as context; see monitoring/README.md.
ARG RUNTIME_IMAGE=concurrent-balance-service-app:latest
FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --chown=10001:10001 concurrent-balance-service-*.jar /app/app.jar
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
