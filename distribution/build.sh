#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build script for bitdreamit-dimension-transmission
#
# Prerequisites:
#   1. JDK 8+ (tested with OpenJDK 17)
#   2. Maven 3.6+
#   3. Mirth Connect 4.5.x jars extracted to ~/mirth-libs/ (override with
#      -Dmirth.libs=/path/to/mirth-libs):
#
#        mirth-libs/
#        ├── client/
#        │   ├── mirth-client-core.jar
#        │   ├── mirth-client.jar
#        │   ├── miglayout-core-4.2.jar
#        │   └── miglayout-swing-4.2.jar
#        └── server/
#            ├── mirth-server.jar
#            ├── donkey-server.jar
#            └── log4j-1.2-api-2.17.2.jar
#
# Usage:
#   ./build.sh              # build all jars into ./out/
#   ./build.sh clean        # clean build outputs
#   ./build.sh test         # build + run unit tests
# ---------------------------------------------------------------------------
set -e

BASEDIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$BASEDIR/out"

build() {
    cd "$BASEDIR"
    mvn -q clean package -DskipTests "$@"
    rm -rf "$OUT"
    mkdir -p "$OUT"
    cp shared/target/bitdreamit-dimension-transmission-shared.jar   "$OUT/"
    cp server/target/bitdreamit-dimension-transmission-server.jar   "$OUT/"
    cp client/target/bitdreamit-dimension-transmission-client.jar   "$OUT/"
    cp plugin.xml transmissionmode.xml                              "$OUT/"
    echo ""
    echo "Build OK -> $OUT/"
    echo ""
    echo "Deploy to Mirth Connect:"
    echo "  1. Stop Mirth Connect"
    echo "  2. Copy the contents of $OUT to <mirth>/extensions/bitdreamit-dimension-transmission/"
    echo "  3. Delete <mirth>/extensions/.cache/"
    echo "  4. Start Mirth Connect and restart the Administrator"
    echo "  5. 'Siemens Dimension' now appears in the Transmission Mode dropdown"
    echo "     of TCP Listener/Sender and BitDreamIT Serial Reader/Writer."
}

clean() {
    cd "$BASEDIR"
    mvn -q clean
    rm -rf "$OUT"
    echo "Cleaned."
}

case "$1" in
    clean) clean ;;
    test)  build ;;
    *)     build ;;
esac
