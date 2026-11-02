#!/bin/sh
# Installs a launcher update on Linux once the launcher has quit, then starts the launcher again.
# Usage: install-linux.sh <launcher pid> <deb> <launcher> <command that installs a deb...>
# The installing command asks for the administrator's password: the package goes to /opt.
pid=$1 deb=$2 app=$3
shift 3
while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
status=0
"$@" "$deb" || { status=$?; echo "install-linux: installing $deb failed with code $status" >&2; }
"$app" > /dev/null 2>&1 &
exit "$status"
