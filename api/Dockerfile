# --- build stage ---
FROM gradle:8.10-jdk21-jammy AS build
WORKDIR /workspace

COPY build.gradle settings.gradle ./
COPY src src
RUN gradle clean bootJar -x test --no-daemon

# --- runtime stage ---
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
