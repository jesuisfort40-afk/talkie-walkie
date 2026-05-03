FROM eclipse-temurin:11-jdk

WORKDIR /app

COPY server/Server.java .

RUN javac Server.java

EXPOSE 55555

CMD ["java", "Server"]
