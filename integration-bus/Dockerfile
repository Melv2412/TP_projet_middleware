# --- Étape 1 : compilation avec Maven ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
# Cache Maven persistant (BuildKit) : les dépendances sont téléchargées une seule fois
# et réutilisées à chaque build, même si le pom.xml change.
RUN --mount=type=cache,target=/root/.m2 mvn -q -B package -DskipTests

# --- Étape 2 : image d'exécution légère ---
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/integration-bus.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
