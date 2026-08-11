FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl services/retrieval -am package -DskipTests

FROM eclipse-temurin:21.0.7_6-jre
WORKDIR /app
COPY --from=build /workspace/services/retrieval/target/*.jar /app/app.jar
EXPOSE 8084
CMD ["java", "-jar", "/app/app.jar"]
