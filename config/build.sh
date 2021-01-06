#!/bin/sh

YAML=900-integrations.yaml

echo "---" > $YAML
kamel run --name github-app \
    -d mvn:commons-codec:commons-codec:1.15 \
    -d mvn:org.bouncycastle:bcpkix-jdk15on:1.68 \
    -d mvn:com.auth0:java-jwt:3.12.0 \
    ../src/main/java/GitHubApp.java \
    ../src/main/java/CallbackHandler.java \
    ../src/main/java/EventsHandler.java \
    -o yaml >> $YAML
