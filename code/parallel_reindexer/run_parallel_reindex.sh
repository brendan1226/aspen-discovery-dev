#!/bin/bash
# ============================================================================
# Run script for the Parallel Reindexer plugin
# ============================================================================
#
# Usage:
#   ./run_parallel_reindex.sh <serverName> [full|fullNoClear|nightly]
#
# This script assembles the classpath from the standard Aspen Discovery
# installation layout and launches the parallel reindexer.
#
# Place parallel_reindexer.properties next to this script to configure
# thread counts, queue sizes, etc.
# ============================================================================

set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 <serverName> [full|fullNoClear|nightly]"
    exit 1
fi

SERVER_NAME="$1"
MODE="${2:-full}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ASPEN_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
REINDEXER_DIR="$ASPEN_ROOT/code/reindexer"
SHARED_LIB_DIR="$ASPEN_ROOT/code/java_shared_libraries"
SOLR_DIST="$ASPEN_ROOT/sites/default/solr-8.11.2/dist"

# Build classpath
CP="$SCRIPT_DIR/parallel_reindexer.jar"
CP="$CP:$REINDEXER_DIR/reindexer.jar"
CP="$CP:$REINDEXER_DIR/lib/normalizer.jar"

# Shared libraries
for jar in "$SHARED_LIB_DIR"/*.jar; do
    [ -f "$jar" ] && CP="$CP:$jar"
done

# Solr JARs
if [ -d "$SOLR_DIST" ]; then
    for jar in "$SOLR_DIST"/*.jar; do
        [ -f "$jar" ] && CP="$CP:$jar"
    done
fi
if [ -d "$SOLR_DIST/solrj-lib" ]; then
    for jar in "$SOLR_DIST/solrj-lib"/*.jar; do
        [ -f "$jar" ] && CP="$CP:$jar"
    done
fi

echo "=== Parallel Reindexer ==="
echo "Server: $SERVER_NAME"
echo "Mode:   $MODE"
echo ""

# JVM settings — tune for your system
# For 3M+ doc collections, consider -Xmx4g or higher
JAVA_OPTS="${JAVA_OPTS:--Xmx2g -Xms1g}"

exec java $JAVA_OPTS \
    -cp "$CP" \
    com.bws.aspen.parallel_reindexer.ParallelReindexMain \
    "$SERVER_NAME" "$MODE"
