FROM eclipse-temurin:21-jre-alpine
COPY /build/libs/MarathonBot-7.14.jar app.jar
CMD ["java", "-jar", "app.jar"]
EXPOSE 8080
