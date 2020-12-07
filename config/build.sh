#!/bin/sh

YAML=900-integrations.yaml

echo "---" > $YAML
kamel run --name gh-app-handler \
    ../src/main/java/GitHubAppHandler.java \
    -o yaml >> $YAML
