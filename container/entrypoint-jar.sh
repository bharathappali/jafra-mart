#!/bin/sh
set -e
: "${EXTRA_JAVA_OPTS:=}"
exec java $JAVA_OPTS $EXTRA_JAVA_OPTS -jar /app/app.jar
