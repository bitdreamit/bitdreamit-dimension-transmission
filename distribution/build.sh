#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Build script for bitdreamit-dimension-transmission (production-ready)
# -----------------------------------------------------------------------------
# Produces three jars (shared / server / client) and copies plugin.xml +
# transmissionmode.xml. The transmissionmode.xml is REQUIRED for extension
# transmission modes - its <sharedClassName> element is what Mirth's
# TransmissionModeController uses to call xStream.allowTypes() to whitelist
# the Properties class. Without it, channel XML deserialization fails with
# ForbiddenClassException. (Mirth's built-in MLLP doesn't need it because
# MLLP's classes are in Mirth's core jars which are already on XStream's
# allow-list.)
#
# Requirements:
#   - JDK 8+ (tested with OpenJDK 17)
#   - Mirth Connect 4.5+ jars unpacked at ../mirth-libs/{server,client,test}/
#
# Usage:
#   cd distribution && ./build.sh            # build all jars
#   cd distribution && ./build.sh clean      # remove out/ folder
#   cd distribution && ./build.sh test      # build + run JUnit tests
# -----------------------------------------------------------------------------
set -e

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$PROJECT_DIR/out"

# --- Resolve Mirth libraries (layout documented in README) ------------------
MIRTH_LIBS_DIR="${MIRTH_LIBS_DIR:-$PROJECT_DIR/../mirth-libs}"
SERVER_LIB="$MIRTH_LIBS_DIR/server"
CLIENT_LIB="$MIRTH_LIBS_DIR/client"
TEST_LIB="$MIRTH_LIBS_DIR/test"

# Shared (model classes) - on both SERVER and CLIENT classpaths
SHARED_MODEL_JAR="$CLIENT_LIB/mirth-client-core.jar"

# donkey-server.jar is required by the shared module because the parent class
# com.mirth.connect.model.transmission.TransmissionModeProperties (in
# mirth-client-core.jar) implements com.mirth.connect.donkey.util.purge.Purgable
# (in donkey-server.jar). Without it the shared module fails to compile with
# "cannot access com.mirth.connect.donkey.util.purge.Purgable".
DONKEY_SERVER_JAR="$SERVER_LIB/donkey-server.jar"

# log4j-1.2-api-2.17.2.jar is required by the server module because
# DimensionStreamHandler.java imports org.apache.log4j.Logger. In Mirth
# Connect 4.5.2, org.apache.log4j.Logger is a bridge class that ships in
# log4j-1.2-api-2.17.2.jar (it delegates to Log4j 2.x internally via
# org.apache.logging.log4j.LogManager). Without this JAR on the server
# compile classpath, javac fails with "package org.apache.log4j does not
# exist" when compiling DimensionStreamHandler.java.
#
# Note: Mirth ships log4j-1.2-api-2.17.2.jar at BOTH server/ and client/
# folders. We use the server/ copy here for the SERVER_CP.
LOG4J_API_JAR="$SERVER_LIB/log4j-1.2-api-2.17.2.jar"

# MigLayout 4.2 ships as TWO jars in Mirth Connect 4.5.x:
#   - miglayout-core-4.2.jar    -> net.miginfocom.layout.* (LC, AC, CC, ...)
#   - miglayout-swing-4.2.jar   -> net.miginfocom.swing.MigLayout
# Kept on the client classpath for parity with the Administrator runtime
# (the Dimension settings panel does not import MigLayout directly, but
# Mirth's client classes loaded next to it do).
MIGLAYOUT_CORE_JAR="$CLIENT_LIB/miglayout-core-4.2.jar"
MIGLAYOUT_SWING_JAR="$CLIENT_LIB/miglayout-swing-4.2.jar"

# Shared compile classpath = client-core + donkey-server (for Purgable)
SHARED_CP="$SHARED_MODEL_JAR:$DONKEY_SERVER_JAR"

# Server-side classpath
SERVER_CP="$SERVER_LIB/mirth-server.jar"
SERVER_CP="$SERVER_CP:$DONKEY_SERVER_JAR"
SERVER_CP="$SERVER_CP:$SHARED_MODEL_JAR"
SERVER_CP="$SERVER_CP:$LOG4J_API_JAR"

# Client-side classpath - includes donkey-server.jar for the same Purgable
# reason: the shared module's TransmissionModeProperties is loaded transitively.
CLIENT_CP="$CLIENT_LIB/mirth-client.jar"
CLIENT_CP="$CLIENT_CP:$DONKEY_SERVER_JAR"
CLIENT_CP="$CLIENT_CP:$SHARED_MODEL_JAR"
CLIENT_CP="$CLIENT_CP:$MIGLAYOUT_CORE_JAR"
CLIENT_CP="$CLIENT_CP:$MIGLAYOUT_SWING_JAR"

# Test classpath (junit + hamcrest + server-side for testing StreamHandler etc.)
TEST_CP="$SERVER_CP:$TEST_LIB/junit-4.13.2.jar"
TEST_CP="$TEST_CP:$TEST_LIB/hamcrest-core-1.3.jar"

# --- Stub fallback -----------------------------------------------------------
# If donkey-server.jar is genuinely not available, fall back to the
# compile-time-only stub interface shipped under stubs/. The stub lets the
# project compile but the real class is still required at runtime.
if [ ! -f "$DONKEY_SERVER_JAR" ]; then
    if [ -d "$PROJECT_DIR/stubs" ]; then
        echo "[build] WARNING: $DONKEY_SERVER_JAR not found."
        echo "[build]          Falling back to compile-time stubs at $PROJECT_DIR/stubs"
        echo "[build]          (DO NOT deploy the produced jars to a Mirth server"
        echo "[build]           that lacks the real Purgable class - see stubs/README.md)"
        STUBS_SOURCEPATH="$PROJECT_DIR/stubs"
        SHARED_CP_EXTRA=":$PROJECT_DIR/stubs"
        SHARED_CP="$SHARED_CP$SHARED_CP_EXTRA"
        CLIENT_CP="$CLIENT_CP$SHARED_CP_EXTRA"
    else
        echo "[build] ERROR: $DONKEY_SERVER_JAR not found and no stubs/ directory."
        echo "[build]        Put donkey-server.jar at $DONKEY_SERVER_JAR"
        echo "[build]        OR add a stubs/ directory with a fallback Purgable interface."
        echo "[build]        (For a syntax-check-only compile without any Mirth"
        echo "[build]         jars, see tools/compile-stubs/README.txt.)"
        exit 2
    fi
fi

# --- Helpers ----------------------------------------------------------------
clean() {
    echo "[clean] removing $OUT_DIR"
    rm -rf "$OUT_DIR"
}

build() {
    echo "[build] project dir: $PROJECT_DIR"
    echo "[build] mirth libs: $MIRTH_LIBS_DIR"
    mkdir -p "$OUT_DIR/shared" "$OUT_DIR/server" "$OUT_DIR/client" "$OUT_DIR/test"

    # 1. Compile shared module.
    #    Classpath MUST include BOTH mirth-client-core.jar (which provides
    #    TransmissionModeProperties / FrameModeProperties /
    #    DataTypePropertyDescriptor) AND donkey-server.jar (which provides
    #    Purgable, the interface TransmissionModeProperties implements).
    #    SHARED_CP is set above to include both. If donkey-server.jar is
    #    missing, SHARED_CP falls back to the stubs/ source root.
    echo "[build] compiling shared..."
    if [ -n "${STUBS_SOURCEPATH:-}" ]; then
        # No donkey-server.jar - use the stubs/ source root to provide Purgable.
        javac -d "$OUT_DIR/shared" \
            -cp "$SHARED_CP" \
            -sourcepath "$PROJECT_DIR/shared/src:$STUBS_SOURCEPATH" \
            $(find "$PROJECT_DIR/shared/src" -name "*.java")
    else
        javac -d "$OUT_DIR/shared" \
            -cp "$SHARED_CP" \
            -sourcepath "$PROJECT_DIR/shared/src" \
            $(find "$PROJECT_DIR/shared/src" -name "*.java")
    fi

    # 2. Compile server module (needs shared + server classpath)
    echo "[build] compiling server..."
    javac -cp "$OUT_DIR/shared:$SERVER_CP" \
        -d "$OUT_DIR/server" \
        -sourcepath "$PROJECT_DIR/server/src${STUBS_SOURCEPATH:+:$STUBS_SOURCEPATH}" \
        $(find "$PROJECT_DIR/server/src" -name "*.java")

    # 3. Compile client module (needs shared + client classpath, which now
    #    also includes donkey-server.jar for Purgable)
    echo "[build] compiling client..."
    javac -cp "$OUT_DIR/shared:$CLIENT_CP" \
        -d "$OUT_DIR/client" \
        -sourcepath "$PROJECT_DIR/client/src${STUBS_SOURCEPATH:+:$STUBS_SOURCEPATH}" \
        $(find "$PROJECT_DIR/client/src" -name "*.java")

    # 4. Compile tests (needs shared + server + junit)
    echo "[build] compiling tests..."
    javac -cp "$OUT_DIR/shared:$OUT_DIR/server:$TEST_CP" \
        -d "$OUT_DIR/test" \
        -sourcepath "$PROJECT_DIR/test/src" \
        $(find "$PROJECT_DIR/test/src" -name "*.java")

    # 5. Package shared jar (Constants + Properties)
    echo "[build] packaging shared jar..."
    jar cf "$OUT_DIR/bitdreamit-dimension-transmission-shared.jar" \
        -C "$OUT_DIR/shared" .

    # 6. Package server jar (shared classes + server classes)
    #    Note: the plugin.xml and transmissionmode.xml are NO LONGER
    #    bundled inside the JARs - they are deployed as separate files
    #    at the extension folder root (Mirth Connect 4.x convention).
    #
    #    We MERGE the shared + server class directories into a single
    #    staging directory before packaging. This avoids the JDK 8
    #    "jar" tool's "duplicate entry: com/" ZipException that occurs
    #    when using two -C options on directories that both contain a
    #    com/ subtree. (JDK 9+ "jar" merges duplicate directory entries
    #    automatically; JDK 8 does not.)
    echo "[build] packaging server jar..."
    rm -rf "$OUT_DIR/server-jar"
    mkdir -p "$OUT_DIR/server-jar"
    cp -r "$OUT_DIR/shared/." "$OUT_DIR/server-jar/"
    cp -r "$OUT_DIR/server/." "$OUT_DIR/server-jar/"
    jar cf "$OUT_DIR/bitdreamit-dimension-transmission-server.jar" \
        -C "$OUT_DIR/server-jar" .

    # 7. Package client jar (shared classes + client classes)
    #    Same merge-into-staging approach as rule #6 above.
    echo "[build] packaging client jar..."
    rm -rf "$OUT_DIR/client-jar"
    mkdir -p "$OUT_DIR/client-jar"
    cp -r "$OUT_DIR/shared/." "$OUT_DIR/client-jar/"
    cp -r "$OUT_DIR/client/." "$OUT_DIR/client-jar/"
    jar cf "$OUT_DIR/bitdreamit-dimension-transmission-client.jar" \
        -C "$OUT_DIR/client-jar" .

    # 8. Copy the production XML files to the output directory.
    #    Both plugin.xml AND transmissionmode.xml are needed:
    #      - plugin.xml: tells Mirth which plugin classes to load
    #      - transmissionmode.xml: tells Mirth's TransmissionModeController
    #        the server/client/shared class names. The <sharedClassName>
    #        element is used to register the Properties class with XStream's
    #        security framework (xStream.allowTypes()). Without it, XStream
    #        rejects the Properties class at channel-deserialization time
    #        with ForbiddenClassException.
    echo "[build] copying production XML files..."
    cp "$PROJECT_DIR/plugin.xml"             "$OUT_DIR/plugin.xml"
    cp "$PROJECT_DIR/transmissionmode.xml"   "$OUT_DIR/transmissionmode.xml"

    echo ""
    echo "[build] complete. Artifacts:"
    ls -la "$OUT_DIR"/*.jar "$OUT_DIR"/*.xml
}

run_tests() {
    build
    echo "[test] running JUnit..."
    java -cp "$OUT_DIR/shared:$OUT_DIR/server:$OUT_DIR/test:$TEST_CP" \
        org.junit.runner.JUnitCore \
        com.bitdreamit.connect.plugins.transmission.dimension.test.DimensionFrameTest
    java -cp "$OUT_DIR/shared:$OUT_DIR/server:$OUT_DIR/test:$TEST_CP" \
        org.junit.runner.JUnitCore \
        com.bitdreamit.connect.plugins.transmission.dimension.test.DimensionSerialPipeTest
    java -cp "$OUT_DIR/shared:$OUT_DIR/server:$OUT_DIR/test:$TEST_CP" \
        org.junit.runner.JUnitCore \
        com.bitdreamit.connect.plugins.transmission.dimension.test.DimensionHL7TranslatorTest
}

# --- Dispatch ---------------------------------------------------------------
case "${1:-build}" in
    clean)    clean ;;
    build)    build ;;
    test)     run_tests ;;
    rebuild)  clean && build ;;
    *) echo "Usage: $0 {clean|build|test|rebuild}"; exit 1 ;;
esac
