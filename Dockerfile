# Use OpenJDK base image
FROM openjdk:23-jdk-slim
WORKDIR /app

# Copy JAR file into container
COPY target/*.jar Attendence-0.0.1-SNAPSHOT.jar
# Run the application
ENTRYPOINT ["java", "-jar", "Attendence-0.0.1-SNAPSHOT.jar","--server.port=3081"]


