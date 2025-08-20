FROM maven:3.8.8-eclipse-temurin-17 AS build
WORKDIR /workspace

# Copy pom first to leverage Docker cache for dependencies
COPY pom.xml mvnw ./
COPY .mvn .mvn
RUN mvn -B -f pom.xml -e -DskipTests dependency:go-offline

# Copy sources and build
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app

# Copy the built jar from the build stage (use wildcard to match artifact name)
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY target/Merban-Capital-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]