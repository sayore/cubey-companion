#!/usr/bin/env bash
set -euo pipefail
instance=${1:?Usage: scripts/install.sh /path/to/minecraft-instance}
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
jar="$root/build/libs/qb2-0.9.1.jar"
[[ -f "$jar" ]] || jar="$root/mods/qb2-0.9.1.jar"
[[ -f "$jar" ]] || { echo 'Build with ./gradlew clean build first' >&2; exit 1; }
[[ -d "$instance" ]] || { echo 'Instance directory does not exist' >&2; exit 1; }
mkdir -p -- "$instance/mods"
if compgen -G "$instance/mods/qb2-*.jar" > /dev/null; then
    echo 'A QB2 JAR already exists; move it out of mods before installing.' >&2; exit 1
fi
cp -n -- "$jar" "$instance/mods/"
echo "Installed QB2 into $instance/mods"
