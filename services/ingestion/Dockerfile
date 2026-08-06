FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
RUN mvn -pl services/ingestion -am package -DskipTests

FROM eclipse-temurin:21.0.7_6-jre
WORKDIR /app
COPY --from=build /workspace/services/ingestion/target/*.jar /app/app.jar
EXPOSE 8082
CMD ["java", "-jar", "/app/app.jar"]
