FROM openjdk:8-jre-alpine

ADD build/libs/server*-fat.jar /app/server.jar
RUN chgrp -R 0 /app/ && chmod -R 775 /app/

WORKDIR /app/

EXPOSE 8080

# NB must use the exec form of ENTRYPOINT if you want to add arguments with CMD
# https://docs.docker.com/engine/reference/builder/#exec-form-entrypoint-example

ENTRYPOINT ["java", "-Dhttp.address=0.0.0.0", "-Xmx64M", "-jar", "server.jar"]
