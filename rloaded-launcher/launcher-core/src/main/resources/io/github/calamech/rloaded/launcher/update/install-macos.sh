#!/bin/sh
# Installs a launcher update on macOS once the launcher has quit, then starts the new launcher.
# Usage: install-macos.sh <launcher pid> <dmg> <app bundle> <command that starts the app...>
# The new bundle is copied next to the old one first, so a failed copy leaves the old one working.
pid=$1 dmg=$2 app=$3
shift 3
while kill -0 "$pid" 2>/dev/null; do sleep 0.2; done
mount=$(mktemp -d) || exit 1
hdiutil attach -nobrowse -readonly -mountpoint "$mount" "$dmg" > /dev/null || exit 1
status=0
rm -rf "$app.new"
if ditto "$mount/Rloaded.app" "$app.new"; then
  rm -rf "$app.old"
  mv "$app" "$app.old" && mv "$app.new" "$app" && rm -rf "$app.old" || status=1
else
  status=1
fi
hdiutil detach "$mount" > /dev/null
rmdir "$mount"
if [ "$status" -ne 0 ]; then
  echo "install-macos: the update could not replace $app" >&2
  [ -d "$app" ] || mv "$app.old" "$app"
fi
"$@" "$app"
exit "$status"
