FROM openjdk:8-jre-alpine

ADD build/libs/client*-fat.jar /app/client.jar
RUN chgrp -R 0 /app/ && chmod -R 775 /app/

WORKDIR /app/

USER 64738

# NB must use the exec form of ENTRYPOINT if you want to add arguments with CMD
# https://docs.docker.com/engine/reference/builder/#exec-form-entrypoint-example

ENTRYPOINT ["java", "-Xmx32M", "-jar", "client.jar"]
