#!/bin/bash
# ============================================================================
# Build script for the Parallel Reindexer plugin
# ============================================================================
#
# Prerequisites:
#   - JDK 11+ (same as Aspen Discovery)
#   - The core reindexer.jar must already be built
#
# Usage:
#   cd code/parallel_reindexer
#   ./build.sh
#
# Output:
#   parallel_reindexer.jar - contains both ParallelReindexMain and TurboMigrationMain
#
# ============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASPEN_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REINDEXER_DIR="$ASPEN_ROOT/code/reindexer"
SHARED_LIB_DIR="$ASPEN_ROOT/code/java_shared_libraries"

# Solr JARs location — adjust if your Solr install is elsewhere
SOLR_DIST="$ASPEN_ROOT/sites/default/solr-8.11.2/dist"
SOLR_LIB="$SOLR_DIST/solrj-lib"

SRC_DIR="$SCRIPT_DIR/src"
BUILD_DIR="$SCRIPT_DIR/build"
OUT_JAR="$SCRIPT_DIR/parallel_reindexer.jar"

echo "=== Building Parallel Reindexer Plugin ==="
echo "Aspen root: $ASPEN_ROOT"

# Clean
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

# Collect classpath
CP=""
# Core reindexer JAR
CP="$CP:$REINDEXER_DIR/reindexer.jar"
# Shared libraries (JARs + compiled classes)
for jar in "$SHARED_LIB_DIR"/*.jar; do
    CP="$CP:$jar"
done
# Shared library compiled classes (if building from source)
if [ -d "$SHARED_LIB_DIR/out" ]; then
    CP="$CP:$SHARED_LIB_DIR/out"
fi
# Normalizer
if [ -f "$REINDEXER_DIR/lib/normalizer.jar" ]; then
    CP="$CP:$REINDEXER_DIR/lib/normalizer.jar"
fi
# Solr client JARs
if [ -d "$SOLR_DIST" ]; then
    for jar in "$SOLR_DIST"/*.jar; do
        CP="$CP:$jar"
    done
fi
if [ -d "$SOLR_LIB" ]; then
    for jar in "$SOLR_LIB"/*.jar; do
        CP="$CP:$jar"
    done
fi
# Remove leading colon
CP="${CP#:}"

echo "Compiling..."
find "$SRC_DIR" -name "*.java" > "$BUILD_DIR/sources.txt"

javac -d "$BUILD_DIR" \
    -cp "$CP" \
    -source 11 -target 11 \
    @"$BUILD_DIR/sources.txt"

echo "Packaging JAR..."
cd "$BUILD_DIR"
jar cfe "$OUT_JAR" com.bws.aspen.parallel_reindexer.ParallelReindexMain \
    com/

echo "=== Build complete: $OUT_JAR ==="
echo ""
echo "Contains two entry points:"
echo ""
echo "  Turbo Reindex (re-index grouped works already in Aspen):"
echo "    java -cp parallel_reindexer.jar:../reindexer/reindexer.jar:../java_shared_libraries/*:../reindexer/lib/* \\"
echo "         com.bws.aspen.parallel_reindexer.ParallelReindexMain <serverName> full"
echo ""
echo "  Turbo Migration (bulk extract from Koha → Aspen DB):"
echo "    java -cp parallel_reindexer.jar:../reindexer/reindexer.jar:../java_shared_libraries/*:../reindexer/lib/* \\"
echo "         com.bws.aspen.parallel_reindexer.TurboMigrationMain <serverName> [workers] [batchSize] [profileName]"
