#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# deploy.sh - assembles a ready-to-drop Mirth Connect extension folder
# -----------------------------------------------------------------------------
#
# Produces a self-contained folder at out/bitdreamit-dimension-transmission/
# containing exactly the files Mirth Connect 4.x needs to load the extension:
#
#   out/bitdreamit-dimension-transmission/
#     plugin.xml                                          <-- extension descriptor
#     transmissionmode.xml                                <-- transmission mode descriptor
#                                                           (REQUIRED for extension
#                                                            transmission modes - its
#                                                            <sharedClassName> tells
#                                                            Mirth's XStream to allow
#                                                            the Properties class)
#     bitdreamit-dimension-transmission-shared.jar        <-- shared classes
#     bitdreamit-dimension-transmission-server.jar        <-- server classes (incl. shared)
#     bitdreamit-dimension-transmission-client.jar        <-- client classes (incl. shared)
#
# Note: Mirth's built-in MLLP doesn't ship a transmissionmode.xml because
# MLLP's Properties class lives in Mirth's core jars (already on XStream's
# allow-list). Extension transmission modes MUST ship one.
#
# Usage:
#   cd distribution && ./deploy.sh             # build + assemble extension folder
#   cd distribution && ./deploy.sh zip          # also produce a .zip of the folder
#   cd distribution && ./deploy.sh install      # build + copy directly to Mirth
#                                              #   (requires MIRTH_HOME env var)
#
# Prerequisites:
#   - JDK 8+ on PATH
#   - Mirth Connect 4.5+ jars unpacked at ../mirth-libs/{server,client,test}/
#   - For 'install' mode: MIRTH_HOME env var pointing at the Mirth install dir
#
# -----------------------------------------------------------------------------
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$PROJECT_DIR/out"
EXT_NAME="bitdreamit-dimension-transmission"
EXT_DIR="$OUT_DIR/$EXT_NAME"

# Step 1: build the JARs using the regular build script
echo "[deploy] step 1/3: building JARs via build.sh..."
"$(dirname "$0")/build.sh" build

# Step 2: assemble the extension folder
echo "[deploy] step 2/3: assembling extension folder at $EXT_DIR..."
rm -rf "$EXT_DIR"
mkdir -p "$EXT_DIR"

# Copy the two XML files (root-level descriptors)
cp "$PROJECT_DIR/plugin.xml"             "$EXT_DIR/plugin.xml"
cp "$PROJECT_DIR/transmissionmode.xml"   "$EXT_DIR/transmissionmode.xml"

# Copy the three JARs
cp "$OUT_DIR/bitdreamit-dimension-transmission-shared.jar"  "$EXT_DIR/"
cp "$OUT_DIR/bitdreamit-dimension-transmission-server.jar"  "$EXT_DIR/"
cp "$OUT_DIR/bitdreamit-dimension-transmission-client.jar"  "$EXT_DIR/"

echo ""
echo "[deploy] extension folder contents:"
ls -la "$EXT_DIR"

# Step 3: optional actions based on the first argument
case "${1:-build}" in
    build)
        echo ""
        echo "[deploy] done. Extension folder ready at:"
        echo "  $EXT_DIR"
        echo ""
        echo "To install in Mirth Connect, copy this folder to:"
        echo "  \$MIRTH_HOME/extensions/$EXT_NAME/"
        echo "then restart the Mirth service."
        ;;

    zip)
        ZIP_PATH="$OUT_DIR/$EXT_NAME.zip"
        echo ""
        echo "[deploy] step 3/3: zipping extension folder to $ZIP_PATH..."
        (cd "$OUT_DIR" && zip -r "$EXT_NAME.zip" "$EXT_NAME/")
        echo ""
        echo "[deploy] done. Production ZIP ready at:"
        echo "  $ZIP_PATH"
        echo ""
        echo "To install: unzip into \$MIRTH_HOME/extensions/ and restart Mirth."
        ;;

    install)
        if [ -z "${MIRTH_HOME:-}" ]; then
            echo "[deploy] ERROR: MIRTH_HOME env var not set."
            echo "[deploy]        Set it to your Mirth Connect install dir, e.g.:"
            echo "[deploy]          export MIRTH_HOME=/opt/mirth-connect"
            echo "[deploy]        then re-run: ./deploy.sh install"
            exit 2
        fi
        TARGET="$MIRTH_HOME/extensions/$EXT_NAME"
        echo ""
        echo "[deploy] step 3/3: installing to $TARGET..."
        if [ -d "$TARGET" ]; then
            echo "[deploy] backing up existing extension to $TARGET.bak..."
            rm -rf "$TARGET.bak"
            mv "$TARGET" "$TARGET.bak"
        fi
        mkdir -p "$TARGET"
        cp "$EXT_DIR"/* "$TARGET/"
        echo ""
        echo "[deploy] done. Installed to:"
        echo "  $TARGET"
        echo ""
        echo "Restart Mirth to pick up the new extension:"
        echo "  sudo systemctl restart mirth-connect"
        ;;

    *)
        echo "Usage: $0 {build|zip|install}"
        exit 1
        ;;
esac
