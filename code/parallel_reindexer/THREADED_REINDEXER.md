# Threaded Reindexer — Stock reindexer.jar Modification

**Date:** 2026-04-09  
**Branch:** `feature/threaded-reindexer`  
**Compatibility:** Aspen Discovery 26.05.00  

---

## Results

| Metric | Value |
|--------|-------|
| **Works processed** | 218,885 |
| **Duration** | **339 seconds (5m 39s)** |
| **Rate** | **~645 works/sec** |
| **Errors** | 0 |
| **Solr docs** | 218,904 |

---

## What Changed in reindexer.jar

Two class files patched into the stock JAR — no other changes:

| File | What Changed | Lines |
|------|-------------|-------|
| **GroupedWorkIndexer.java** | Added 3 fields: `solrThreadCount`, `solrQueueSize`, `numReindexWorkerThreads` | +3 field declarations |
| | Load new settings from `system_variables` table (with try/catch fallback) | +3 lines in constructor |
| | Solr client uses `solrThreadCount`/`solrQueueSize` instead of hardcoded `1`/`25` | Changed 2 lines |
| | Added getter `getNumReindexWorkerThreads()` | +3 lines |
| **GroupedReindexMain.java** | Added imports: `java.util.concurrent.*`, `AtomicLong` | +2 imports |
| | Routing: if `numReindexWorkerThreads > 1` AND full reindex → call `processGroupedWorksThreaded()` | +5 lines in main |
| | New method `processGroupedWorksThreaded()` — producer/consumer with N workers, each with own DB conn + indexer | +120 lines |

---

## Configuration

Controlled by `system_variables` table (Aspen admin UI → System Administration → System Variables):

| Setting | Stock Default | Recommended | Effect |
|---------|--------------|-------------|--------|
| `solrThreadCount` | 1 | **4** | Solr HTTP client threads per indexer instance |
| `solrQueueSize` | 25 | **200** | Solr document buffer before blocking |
| `numReindexWorkerThreads` | 1 | **4** | Parallel indexing workers for full reindex |

Set `numReindexWorkerThreads = 1` to revert to stock single-threaded behavior without swapping JARs.

Threading only activates for **full reindex** (`fullNoClear` or `nightly` mode). Incremental reindexes always run single-threaded.

### Database Migration

Add the columns to `system_variables` (run once):

```sql
ALTER TABLE system_variables 
  ADD COLUMN IF NOT EXISTS solrThreadCount int(11) DEFAULT 1,
  ADD COLUMN IF NOT EXISTS solrQueueSize int(11) DEFAULT 25,
  ADD COLUMN IF NOT EXISTS numReindexWorkerThreads int(11) DEFAULT 1;

-- Set recommended values:
UPDATE system_variables SET solrThreadCount = 4, solrQueueSize = 200, numReindexWorkerThreads = 4;
```

---

## How It Works

### Stock Reindexer (single-threaded)

```
Single thread, single DB connection, Solr client (1 thread, queue 25)

for each grouped_work (sequential):
    processGroupedWork()     ← 5-10 DB queries
    add to Solr queue        ← 1 HTTP thread
    commit every 10,000
```

### Threaded Reindexer (multi-threaded)

```
Producer thread (own DB conn)         → BlockingQueue(10000)
  Streams all work IDs                     ↓
                                    ┌──────┼──────┐
                                    ▼      ▼      ▼
                              Worker-0  Worker-1  Worker-N
                              Own DB    Own DB    Own DB
                              Own Indexer Own Indexer Own Indexer
                              Solr(4t)  Solr(4t)  Solr(4t)
```

Each worker:
1. Creates its own JDBC connection
2. Creates its own `GroupedWorkIndexer` instance (with its own Solr client, PreparedStatements, caches)
3. Pulls work IDs from the shared `BlockingQueue`
4. Loads work details and calls `processGroupedWork()` independently
5. Shuts down on poison pill from producer

---

## Build Instructions

The stock `reindexer.jar` is a thin JAR — it does NOT bundle third-party dependencies.
It relies on shared libraries being on the classpath at runtime.

### Option A: Patch the stock JAR (what we did)

```bash
# 1. Compile shared libraries
BUILD_SHARED=/tmp/shared_build
find code/java_shared_libraries/src -name "*.java" > /tmp/sources.txt
javac -d $BUILD_SHARED -cp "code/java_shared_libraries/*:sites/default/solr-8.11.2/dist/*:sites/default/solr-8.11.2/dist/solrj-lib/*" \
    -source 11 -target 11 @/tmp/sources.txt

# 2. Compile modified reindexer classes
BUILD_DIR=/tmp/reindexer_build
CP="$BUILD_SHARED:$BUILD_DIR:code/java_shared_libraries/*:code/reindexer/lib/*:sites/default/solr-8.11.2/dist/solr-solrj-8.11.2.jar:sites/default/solr-8.11.2/dist/solrj-lib/*"
# Remove stale module-info if present
rm -f $BUILD_DIR/module-info.class $BUILD_DIR/META-INF/versions/9/module-info.class
javac -d $BUILD_DIR -cp "$CP" -source 11 -target 11 \
    code/reindexer/src/org/aspen_discovery/reindexer/GroupedReindexMain.java

# 3. Patch stock JAR
cp code/reindexer/reindexer.jar.stock code/reindexer/reindexer.jar
cd $BUILD_DIR
jar uf /path/to/code/reindexer/reindexer.jar \
    org/aspen_discovery/reindexer/GroupedReindexMain.class \
    org/aspen_discovery/reindexer/GroupedWorkIndexer.class
```

### Option B: Build via IntelliJ (recommended for production)

Open the Aspen project in IntelliJ IDEA, make the changes to the two Java files,
and build the `reindexer` artifact as usual.

---

## Deployment

### Docker (ADB)

```bash
# Backup stock jar
docker exec containeraspen cp /usr/local/aspen-discovery/code/reindexer/reindexer.jar \
    /usr/local/aspen-discovery/code/reindexer/reindexer.jar.stock

# Deploy patched jar
docker cp reindexer.jar containeraspen:/usr/local/aspen-discovery/code/reindexer/reindexer.jar

# Also deploy compiled shared library classes (needed for runtime classpath)
docker cp /tmp/shared_build containeraspen:/tmp/shared_build
```

### Running (Docker)

The stock `java -jar reindexer.jar` relies on classpath resolution that may not work
in all environments. Use explicit `-cp` instead:

```bash
ASPEN=/usr/local/aspen-discovery
CP="/tmp/shared_build"
CP="$CP:$ASPEN/code/reindexer/reindexer.jar"
CP="$CP:$ASPEN/code/reindexer/lib/normalizer.jar"
for jar in $ASPEN/code/java_shared_libraries/*.jar; do CP="$CP:$jar"; done
for jar in $ASPEN/sites/default/solr-8.11.2/dist/*.jar; do CP="$CP:$jar"; done
for jar in $ASPEN/sites/default/solr-8.11.2/dist/solrj-lib/*.jar; do CP="$CP:$jar"; done

java -Xmx2g -cp "$CP" org.aspen_discovery.reindexer.GroupedReindexMain dev.localhost fullNoClear
```

---

## Rollback

```bash
# Restore stock JAR
docker exec containeraspen cp /usr/local/aspen-discovery/code/reindexer/reindexer.jar.stock \
    /usr/local/aspen-discovery/code/reindexer/reindexer.jar

# Or just set threads back to 1 (no JAR swap needed):
# UPDATE system_variables SET numReindexWorkerThreads = 1;
```

---

## Notes

- The threading only activates for full reindex mode, not incremental
- Each worker creates its own `GroupedWorkIndexer` with its own DB connection, Solr client, and caches
- Memory usage scales with worker count: ~300-500MB per worker
- The producer thread uses its own DB connection for the streaming ResultSet
- Progress is logged every 10,000 works to the `reindex_log` table
- Errors are counted but non-fatal — individual work failures don't stop other workers
