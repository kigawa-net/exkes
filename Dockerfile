# Build any module with: docker build --build-arg MODULE=<module> .
#
# We deliberately use `installDist` (a script + one JAR per dependency) instead
# of shadowJar (a fat JAR): Flyway scans the classpath for db/migration SQL
# files and does not find them reliably inside a shaded/fat JAR, so every
# migration would silently be skipped (experienced in ai-scheduler; plan
# chapter 4). Do not switch this to shadowJar.

ARG MODULE

# --- Build stage ------------------------------------------------------------
FROM gradle:jdk21 AS builder

ARG MODULE

WORKDIR /app

# Copy the build scripts of every module first: settings.gradle.kts includes
# all five modules, so Gradle needs their build files even when only one
# module is built. This layer is cached as long as the build scripts do not
# change.
COPY gradle/ gradle/
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY exkes-common/build.gradle.kts exkes-common/
COPY exkes-api/build.gradle.kts exkes-api/
COPY exkes-controller/build.gradle.kts exkes-controller/
COPY exkes-terminal-gateway/build.gradle.kts exkes-terminal-gateway/
COPY runtime-agent/build.gradle.kts runtime-agent/

# Resolve (download) the runtime classpath before copying sources so that
# source-only changes reuse this layer.
RUN ./gradlew --no-daemon ":${MODULE}:dependencies" --configuration runtimeClasspath

# exkes-common is a project dependency of every control plane module.
COPY exkes-common/ exkes-common/
COPY ${MODULE}/ ${MODULE}/

# gradle-wrapper.properties pins Gradle 8.14, so ./gradlew is used instead of
# the Gradle version baked into the base image.
RUN ./gradlew --no-daemon ":${MODULE}:installDist"

# --- Runtime stage ----------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy

ARG MODULE
ENV MODULE=${MODULE}

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r exkes && useradd -r -g exkes exkes

WORKDIR /app

# installDist layout: build/install/<module>/{bin,lib}
COPY --from=builder /app/${MODULE}/build/install/${MODULE}/ ./

RUN chown -R exkes:exkes /app
USER exkes

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

EXPOSE 8080

# JVM options.
#
# -Xmx is deliberately NOT set. An explicit heap ceiling makes the JVM size the
# heap from that constant instead of the cgroup memory limit, so the pod in
# exkes/{stg,main}/exkes-api.yaml (limits.memory: 512Mi, plan chapter 9.4) would
# be OOMKilled as soon as the heap grew past the limit — a landmine that only
# shows up under load. MaxRAMPercentage derives the heap from the limit the JVM
# actually observes (UseContainerSupport is default from JDK 10 on and always
# enabled), so 70% of 512Mi ~= 350Mi leaves headroom for metaspace, code cache,
# direct buffers and thread stacks.
#
# -Xms128m is safe to keep: the initial heap is only an allocation hint, never
# an upper bound.
ENV JAVA_OPTS="-Xms128m -XX:MaxRAMPercentage=70 -XX:+UseG1GC"

# exec so the JVM becomes PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec bin/$MODULE"]
