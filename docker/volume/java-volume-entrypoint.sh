#!/bin/sh
set -eu

jar_path=${MINIPAY_JAR_PATH:-/app/app.jar}
if [ ! -r "$jar_path" ]; then
  echo "Mounted application JAR is missing or unreadable: $jar_path" >&2
  exit 66
fi

exec java ${JAVA_TOOL_OPTIONS:-} -jar "$jar_path" "$@"
