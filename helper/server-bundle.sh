#!/bin/sh
# server-bundle.sh: a server's mods and configs from a CurseForge client
# instance, with the ccnix mod added.
#
#   helper/server-bundle.sh INSTANCE [OUT.tar.gz]
#
#   INSTANCE  the client instance's folder (the one holding mods/, config/
#             and minecraftinstance.json)
#   OUT       the bundle, server-bundle.tar.gz unless named
#
# The bundle holds mods/, config/ and defaultconfigs/ for the server's
# folder: the instance's mods less the ones a dedicated server can't load
# (CLIENT_ONLY below), less any CC: Tweaked older than 1.120 (a pack update
# lists one beside the newer jar, which NeoForge would ignore anyway; ccnix
# and the pack's Advanced Peripherals need 1.120 and 1.119), and build/libs' ccnix jar.
# Runs in Git Bash on Windows or on Linux; build the mod first.
set -e

# jars (by name prefix) that stop a dedicated server: client-only mods
CLIENT_ONLY="sodium-"

[ $# -ge 1 ] || { sed -n '2,17s/^# \{0,1\}//p' "$0"; exit 2; }
inst=$1
out=${2:-server-bundle.tar.gz}
here=$(cd "$(dirname "$0")/.." && pwd)
jar=$(ls "$here"/build/libs/ccnix-*.jar 2>/dev/null | grep -v -- '-sources' | sort -V | tail -1)   # (the newest version)
[ -d "$inst/mods" ] || { echo "no mods/ in $inst" >&2; exit 1; }
[ -n "$jar" ] || { echo "no ccnix jar in build/libs: ./gradlew build first" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/mods"
for f in "$inst"/mods/*.jar; do
    n=$(basename "$f")
    skip=
    for p in $CLIENT_ONLY; do case $n in "$p"*) skip="client-only";; esac; done
    case $n in
        cc-tweaked-*-forge-1.1[01][0-9].*) skip="CC: Tweaked older than 1.120";;
        ccnix-*) skip="the instance's own ccnix (the built one goes in)";;
    esac
    if [ -n "$skip" ]; then echo "  left out: $n ($skip)"; else cp "$f" "$tmp/mods/"; fi
done
cp "$jar" "$tmp/mods/"
for d in config defaultconfigs; do [ -d "$inst/$d" ] && cp -r "$inst/$d" "$tmp/"; done

cc=$(ls "$tmp/mods" | grep -c '^cc-tweaked-' || true)
[ "$cc" = 1 ] || { echo "$cc CC: Tweaked jars in the bundle: want exactly one, 1.120 or later" >&2; exit 1; }
tar -czf "$out" -C "$tmp" .
echo "$out: $(ls "$tmp/mods" | wc -l | tr -d ' ') mods, with $(basename "$jar")"
