#!/bin/bash
./gradlew shadowJar
java -jar build/libs/*.jar
