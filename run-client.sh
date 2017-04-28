#!/bin/bash
export PROXYHOOK_PASSWORD='highlyS3cur3Pa55w0rd'
./gradlew :client:run -Durls='http://localhost:8080/listen http://requestb.in/1kdl0c51'
