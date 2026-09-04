FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
ARG MODULE
COPY ${MODULE}/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
