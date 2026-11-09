#!/bin/bash
# macOS: dvojklik spustí PTZ Bridge
cd "$(dirname "$0")"
if ! command -v node >/dev/null 2>&1; then
  for p in /usr/local/bin /opt/homebrew/bin; do [ -x "$p/node" ] && export PATH="$p:$PATH"; done
fi
if ! command -v node >/dev/null 2>&1; then
  osascript -e 'display alert "Chýba Node.js" message "Nainštaluj Node.js (verzia LTS) z nodejs.org a spusti PTZ Bridge znova."'
  open "https://nodejs.org/"
  exit 1
fi
# caffeinate: Mac počas behu programu nezaspí (inak by tablet stratil spojenie)
if command -v caffeinate >/dev/null 2>&1; then caffeinate -is node bridge.js; else node bridge.js; fi
