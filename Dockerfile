# =============================================================
# Stage 1 — BUILD
# =============================================================
# Uses the official Maven image with Java 21 (LTS).
# Java 21 is used because Spring Boot 3.5.x requires Java 17+
# and 21 is the latest LTS with the best long-term support.
# We do NOT use Java 26 (your local version) — Alpine images
# for 26 are not yet stable in ECR/ECS environments.
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy pom.xml first and download all dependencies.
# Docker caches this layer — if pom.xml hasn't changed,
# the next build skips the download step entirely (much faster).
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Now copy source code and build the fat JAR.
# -DskipTests: tests run in CI, not inside Docker build.
COPY src ./src
RUN mvn package -DskipTests -q


# =============================================================
# Stage 2 — RUNTIME
# =============================================================
# Minimal JRE-only image (no compiler, no Maven).
# Result: ~220 MB image instead of ~700 MB if we used the build image.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Install Tesseract OCR + English language data.
# Your local setup uses /opt/homebrew/lib (macOS).
# Inside this Linux container the library is at the standard Alpine path,
# so the app.tesseract.library.path property must point to /usr/lib.
RUN apk add --no-cache \
    tesseract-ocr \
    tesseract-ocr-data-eng \
    # fontconfig + freetype are required by some PDF/image libraries
    fontconfig \
    freetype \
    # wget is used by the HEALTHCHECK below
    wget

# Copy the fat JAR built in Stage 1.
# The wildcard handles the version suffix (finsight-ai-0.0.1-SNAPSHOT.jar).
COPY --from=build /app/target/finsight-ai-*.jar app.jar

# Tell Docker (and ECS) that this container listens on 8080.
EXPOSE 8080

# ECS uses this health check to decide if the task is healthy.
# If /actuator/health returns non-200 three times, ECS replaces the task.
HEALTHCHECK \
    --interval=30s \
    --timeout=10s \
    --start-period=90s \
    --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# JVM tuning flags for a container environment:
#   -XX:+UseContainerSupport          → JVM reads CPU/RAM limits from the container,
#                                        not the host machine (critical in Fargate)
#   -XX:MaxRAMPercentage=75.0         → Use up to 75% of the container's RAM for heap.
#                                        Leaves room for off-heap (Netty, Cohere client, etc.)
#   -Djava.security.egd=...           → Faster startup: use /dev/urandom instead of /dev/random
#                                        (avoids SecureRandom blocking on entropy-starved containers)
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
