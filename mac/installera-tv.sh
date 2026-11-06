#!/bin/bash
# Installerar eller uppdaterar Tv på en Mac.
#
# Körs i Terminal med kommandot som står under Releases, "Tv för Mac":
#   curl -fsSL https://github.com/<ägare>/<repo>/releases/download/mac-senaste/installera-tv.sh | bash
#
# Väljer rätt version för datorn (Apple Silicon eller Intel), stänger Tv om
# den är igång, lägger den nya i Program och startar den. Källor, historik
# och inställningar ligger kvar, de sparas utanför appen.
#
# GitHub-bygget byter __REPO__ mot repots namn när skriptet publiceras.

# Allt ligger i en funktion: bash läser då hela skriptet innan något körs,
# så en avbruten nedladdning kan aldrig köra halva.
installera() {
    set -euo pipefail

    local repo="__REPO__"
    local base="https://github.com/$repo/releases/download/mac-senaste"

    if [ "$(uname -s)" != "Darwin" ]; then
        echo "Det här är installationen för Mac."
        exit 1
    fi

    # Apple Silicon eller Intel. En Terminal som körs genom Rosetta svarar
    # x86_64 även på Apple Silicon, så den frågan ställs också.
    local file kind
    if [ "$(uname -m)" = "arm64" ] || [ "$(sysctl -in sysctl.proc_translated 2>/dev/null || true)" = "1" ]; then
        file="Tv-Mac-AppleSilicon.dmg"
        kind="Apple Silicon"
    else
        file="Tv-Mac-Intel.dmg"
        kind="Intel"
    fi

    local tmp
    tmp="$(mktemp -d)"
    local volume="$tmp/volym"
    trap 'hdiutil detach -quiet "'"$volume"'" 2>/dev/null || true; rm -rf "'"$tmp"'"' EXIT

    echo "Hämtar Tv för Mac ($kind)…"
    if ! curl -fL --progress-bar -o "$tmp/Tv.dmg" "$base/$file"; then
        echo
        echo "Kunde inte hämta $file. Har bygget \"Build Mac\" på GitHub gått klart?"
        exit 1
    fi

    mkdir -p "$volume"
    hdiutil attach -nobrowse -readonly -noautoopen -quiet -mountpoint "$volume" "$tmp/Tv.dmg" < /dev/null

    # Är Tv igång stängs den först. Positionen i det som spelas sparas var
    # tionde sekund, så högst några sekunder går förlorade.
    if pgrep -f "Tv.app/Contents/MacOS/Tv" > /dev/null 2>&1; then
        echo "Stänger Tv…"
        pkill -TERM -f "Tv.app/Contents/MacOS/Tv" 2>/dev/null || true
        local i
        for i in 1 2 3 4 5 6 7 8 9 10; do
            pgrep -f "Tv.app/Contents/MacOS/Tv" > /dev/null 2>&1 || break
            sleep 1
        done
        pkill -KILL -f "Tv.app/Contents/MacOS/Tv" 2>/dev/null || true
    fi

    # Program-mappen, eller den egna om kontot inte får skriva där.
    local target="/Applications"
    if [ ! -w "$target" ]; then
        target="$HOME/Applications"
        mkdir -p "$target"
    fi

    echo "Installerar i $target…"
    rm -rf "$target/Tv.app"
    ditto "$volume/Tv.app" "$target/Tv.app"
    # Hämtat med curl är appen inte märkt som nedladdad, men säkert är säkert:
    # då frågar macOS inget när den startar.
    xattr -dr com.apple.quarantine "$target/Tv.app" 2>/dev/null || true

    echo "Klart. Tv startar nu, och finns sedan i Program och Launchpad."
    open "$target/Tv.app"
}

installera "$@"
