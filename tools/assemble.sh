#!/bin/sh
# Builds _site/, the exact tree GitHub Pages serves: the reader with its data beside it.
# Used by the publish workflow and for local preview:
#   sh tools/assemble.sh && python3 -m http.server 8765 --bind 127.0.0.1 --directory _site
set -eu
cd "$(dirname "$0")/.."
rm -rf _site
mkdir _site
cp -R site/. _site/
cp -R editions _site/editions
cp profile.json _site/profile.json
