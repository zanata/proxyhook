#!/bin/bash
export PROXYHOOK_PASSWORD='highlyS3cur3Pa55w0rd'
./gradlew run -Durls='http://localhost:8080/listen http://httpresponder.com/proxyhook'
