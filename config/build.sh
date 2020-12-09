#!/bin/sh

YAML=900-integrations.yaml

echo "---" > $YAML
kamel run --name github-app \
    ../src/main/java/GitHubApp.java \
    ../src/main/java/CallbackHandler.java \
    ../src/main/java/EventsHandler.java \
    -o yaml >> $YAML
