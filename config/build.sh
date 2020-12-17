#!/bin/sh

YAML=900-integrations.yaml

echo "---" > $YAML
kamel run --name github-app \
    -d mvn:commons-codec:commons-codec:1.15 \
    ../src/main/java/GitHubApp.java \
    ../src/main/java/CallbackHandler.java \
    ../src/main/java/EventsHandler.java \
    -o yaml >> $YAML
