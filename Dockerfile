# ---------- Build stage ----------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . /app
RUN javac SimpleWebServer.java

# ---------- Run stage ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app /app
ENV PORT=8080
EXPOSE 8080
CMD ["java", "SimpleWebServer"]
