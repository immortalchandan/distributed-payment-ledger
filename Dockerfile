# Layer 1: Build the application with multi-mirror repository fallbacks
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN chmod +x ./gradlew && ./gradlew bootJar -x test --no-daemon --stacktrace

# Layer 2: Ultra-clean runtime container
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
