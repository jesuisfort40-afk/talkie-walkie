FROM openjdk:11-jdk-slim

WORKDIR /app

COPY server/Server.java .

RUN javac Server.java

EXPOSE 55555

CMD ["java", "Server"]
