#!/bin/sh

. ./build.sh
kubectl apply -f $YAML
