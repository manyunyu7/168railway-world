#!/bin/sh
set -eu
exec sh "$(dirname "$0")/build-android.sh" arm64-v8a
