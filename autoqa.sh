#!/usr/bin/env bash
# IMDS AutoQA — portable Linux / macOS launcher
#
# HOW TO USE
#   1. Put autoqa.jar and autoqa.sh in the same folder (anywhere).
#   2. chmod +x autoqa.sh
#   3. ./autoqa.sh        — opens the GUI
#      ./autoqa.sh play recording.json  — runs a CLI command directly
#
# REQUIREMENTS
#   Java 17+ must be installed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/autoqa.jar"

# ── Find java ────────────────────────────────────────────────────────────────

find_java() {
    # 1. Bundled JRE next to the JAR
    if [ -x "$SCRIPT_DIR/jre/bin/java" ]; then
        echo "$SCRIPT_DIR/jre/bin/java"; return
    fi
    # 2. JAVA_HOME
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"; return
    fi
    # 3. PATH
    if command -v java &>/dev/null; then
        echo "java"; return
    fi
    echo ""
}

JAVA="$(find_java)"

if [ -z "$JAVA" ]; then
    echo ""
    echo "  ERROR: Java 17+ is required but was not found."
    echo "  Install it from: https://adoptium.net/"
    echo ""
    exit 1
fi

if [ ! -f "$JAR" ]; then
    echo ""
    echo "  ERROR: autoqa.jar not found in $SCRIPT_DIR"
    echo "  Make sure autoqa.jar and autoqa.sh are in the same folder."
    echo ""
    exit 1
fi

cd "$SCRIPT_DIR"

# Pass any CLI args through; no args → GUI opens
if [ $# -eq 0 ]; then
    # GUI mode — detach so the terminal stays usable
    nohup "$JAVA" -jar "$JAR" "$@" >/dev/null 2>&1 &
else
    # CLI mode — stay attached so output is visible
    "$JAVA" -jar "$JAR" "$@"
fi
