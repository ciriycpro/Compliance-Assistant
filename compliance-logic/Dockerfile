# Multi-stage build для compliance-logic-service
# Triggers миграции на Docker — см. DEC-007

# === Stage 1: builder ===
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Кэшируем зависимости отдельно (быстрая пересборка при изменении только исходников)
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN ./mvnw dependency:go-offline -B

# Копируем исходники и собираем
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# === Stage 2: runtime ===
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Non-root user (DEC-017 Уровень 1)
RUN addgroup -S compliance && adduser -S compliance -G compliance

# Только jar из builder-stage
COPY --from=builder /build/target/compliance-logic-*.jar app.jar
RUN chown compliance:compliance /app/app.jar

USER compliance

EXPOSE 8771

# Listen 0.0.0.0 в Docker (DEC-007 правило 2)
ENV SERVER_ADDRESS=0.0.0.0

# Healthcheck (DEC-007 правило 4)
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8771/actuator/health || exit 1

# Graceful shutdown (DEC-007 правило 7)
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
