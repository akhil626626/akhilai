FROM node:22-bookworm-slim AS frontend
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /app
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml -B dependency:go-offline
COPY backend/ backend/
COPY --from=frontend /app/frontend/dist/akhil-ai/browser frontend/dist/akhil-ai/browser
RUN mvn -f backend/pom.xml -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend /app/backend/target/akhil-api-2.0.0.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
USER 10001
ENTRYPOINT ["java", "-jar", "app.jar"]
