# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 – Build
#   GraalVM 25 Community Edition with the native-image tool pre-installed.
#   The project's own ./mvnw wrapper is used so no separate Maven installation
#   is required.
# ─────────────────────────────────────────────────────────────────────────────
FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /app

# ── Maven wrapper (keep as an early layer for cache efficiency) ───────────────
COPY mvnw mvnw.cmd ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw

# ── Resolve dependencies before copying source (layer cache) ─────────────────
COPY pom.xml ./
RUN ./mvnw dependency:go-offline -q

# ── Application source ────────────────────────────────────────────────────────
COPY src/ src/

# ── Compile a GraalVM native image ───────────────────────────────────────────
# -Pnative activates Spring Boot's AOT processing profile defined in the
# spring-boot-starter-parent.  native:compile then invokes GraalVM native-image.
RUN ./mvnw -Pnative native:compile -DskipTests

# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 – Runtime
#   The native binary is fully self-contained (no JVM required).
#   Use a minimal Debian Bookworm slim layer for glibc compatibility.
# ─────────────────────────────────────────────────────────────────────────────
FROM debian:12-slim AS runtime

# Non-root user for security
RUN addgroup --system appgroup \
 && adduser  --system --ingroup appgroup --no-create-home appuser

# Upload directory used by the ingestion service
RUN mkdir -p /data/rag-uploads \
 && chown appuser:appgroup /data/rag-uploads

WORKDIR /app

# Only the native binary is copied – no JDK, no fat jar
COPY --from=builder /app/target/ingestion-service ./ingestion-service
RUN chmod +x ingestion-service

USER appuser

# Ingestion API port (matches server.port in application.yaml)
EXPOSE 8081

ENTRYPOINT ["/app/ingestion-service"]

