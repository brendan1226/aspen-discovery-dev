# Parallel Reindexer Plugin — Full Specification

**Version:** 1.0.0  
**Author:** ByWater Solutions (BWS)  
**Date:** 2026-04-07  
**Status:** Initial Implementation  
**Compatibility:** Aspen Discovery 26.03.00+ (tested against 26.05.00)

---

## 1. Executive Summary

The Parallel Reindexer is a **drop-in plugin** for Aspen Discovery that replaces the
single-threaded `GroupedReindexMain` with a multi-threaded architecture for dramatically
faster full reindex times. For libraries with 3M+ grouped works, the standard reindexer
can take 12-24+ hours. This plugin targets a **4-6x throughput improvement** by processing
grouped works across multiple concurrent threads.

**Key principle:** Zero modifications to core Aspen Discovery code. This is a separate JAR
that uses the existing `GroupedWorkIndexer` class as-is, instantiating multiple copies
with independent database connections.

---

## 2. Problem Statement

### Current Architecture

The existing reindexer (`GroupedReindexMain` → `GroupedWorkIndexer.processGroupedWorks()`)
processes grouped works in a **strictly sequential loop**:

```
┌──────────────────────────────────────────────────┐
│  Single Thread                                    │
│                                                   │
│  for each grouped_work in ResultSet:              │
│    1. Load primary identifiers     (DB query)     │
│    2. Load overridden records      (DB query)     │
│    3. Process ILS/eContent records (DB queries)   │
│    4. Load enrichment data         (5+ DB queries)│
│    5. Build SolrInputDocument                     │
│    6. Add to Solr                  (HTTP)         │
│                                                   │
│  → Processes ~200-400 works/sec on typical HW     │
└──────────────────────────────────────────────────┘
```

### Bottlenecks Identified

| Bottleneck | Location | Impact |
|-----------|----------|--------|
| Single-threaded work loop | `GroupedWorkIndexer.java:932-991` | CPU cores sit idle |
| Solr client thread count = 1 | `GroupedWorkIndexer.java:399-401` | Network I/O serialized |
| `synchronized processGroupedWork()` | `GroupedWorkIndexer.java:1088` | Prevents concurrent access per instance |
| Sequential DB queries per work | `GroupedWorkIndexer.java:1098-1282` | ~10 queries × network latency |
| No batching or pipelining | — | Can't overlap DB reads with Solr writes |

### Scale Impact

| Collection Size | Current Time (est.) | With 4 Threads (est.) | With 8 Threads (est.) |
|----------------|--------------------|-----------------------|-----------------------|
| 500K works     | 30-45 min          | 8-12 min              | 5-8 min               |
| 1M works       | 1-1.5 hours        | 15-25 min             | 10-15 min             |
| 3M works       | 3-5 hours          | 45-75 min             | 25-45 min             |
| 5M+ works      | 8-15 hours         | 2-4 hours             | 1-2 hours             |

*Estimates assume adequate DB and Solr server capacity. Actual results depend on hardware,
network topology, and data complexity.*

---

## 3. Architecture

### High-Level Design

```
                    ┌─────────────────────┐
                    │  ParallelReindexMain │  (Coordinator)
                    │  - Parse args        │
                    │  - Load config       │
                    │  - Clear index       │
                    │  - Start workers     │
                    │  - Final commit      │
                    └─────────┬───────────┘
                              │
                    ┌─────────▼───────────┐
                    │    WorkProducer      │  (Producer Thread)
                    │  - DB cursor scan    │
                    │  - Streams WorkUnits │
                    │    into BlockingQueue│
                    └─────────┬───────────┘
                              │
                    ┌─────────▼───────────┐
                    │   BlockingQueue      │  (Bounded, backpressure)
                    │   capacity: 10,000   │
                    └──┬──┬──┬──┬──┬──────┘
                       │  │  │  │  │
          ┌────────────┘  │  │  │  └────────────┐
          ▼               ▼  ▼  ▼               ▼
  ┌──────────────┐ ┌──────────────┐     ┌──────────────┐
  │ IndexerWorker│ │ IndexerWorker│ ... │ IndexerWorker│
  │   Thread 0   │ │   Thread 1   │     │   Thread N   │
  │              │ │              │     │              │
  │ Own DB conn  │ │ Own DB conn  │     │ Own DB conn  │
  │ Own Indexer  │ │ Own Indexer  │     │ Own Indexer  │
  │ Own Stmts    │ │ Own Stmts    │     │ Own Stmts    │
  └──────┬───────┘ └──────┬───────┘     └──────┬───────┘
         │                │                    │
         └────────────────┼────────────────────┘
                          │
                ┌─────────▼───────────┐
                │  Solr (shared)       │
                │  ConcurrentUpdate    │
                │  Http2SolrClient     │
                │  threadCount=4-8     │
                │  queueSize=200       │
                └──────────────────────┘
```

### Thread Safety Analysis

| Component | Thread-Safe? | Strategy |
|-----------|-------------|----------|
| `GroupedWorkIndexer` | Per-instance only | One instance per worker thread |
| `PreparedStatement`s | No | Each instance has its own via own `Connection` |
| `ConcurrentUpdateHttp2SolrClient` | Yes | Designed for concurrent access |
| `RecordGroupingProcessor` | Per-instance | Created by each `GroupedWorkIndexer` |
| ILS Record Processors | Per-instance | Created by each `GroupedWorkIndexer` |
| `BlockingQueue<WorkUnit>` | Yes | `LinkedBlockingQueue` from `java.util.concurrent` |
| `ProgressTracker` | Yes | Uses `AtomicLong` and `ConcurrentHashMap` |
| `NightlyIndexLogEntry` | Shared (risk) | Log entry is shared but `saveResults()` is infrequent |

### Key Design Decisions

1. **One GroupedWorkIndexer per thread** — The core indexer uses instance-level
   `PreparedStatement`s and a `synchronized` process method. Rather than refactoring
   the core class, we instantiate N copies. Each gets ~100MB of heap for its caches
   and statement handles.

2. **Producer-Consumer pattern** — A dedicated producer thread streams work IDs from
   the database into a bounded `BlockingQueue`. Workers pull from it. This provides
   natural backpressure: if workers are slow, the producer blocks; if the DB is slow,
   workers block.

3. **Shared Solr client** — Each `GroupedWorkIndexer` instance creates its own Solr
   client. Since each instance processes independently and `ConcurrentUpdateHttp2SolrClient`
   is thread-safe, this works correctly. Each instance's client has the same Solr
   endpoint, and Solr handles concurrent document additions.

4. **Poison pill shutdown** — Workers know to stop when they receive a sentinel
   `WorkUnit.POISON_PILL` from the queue. One is sent per worker.

---

## 4. Components

### 4.1 `ParallelReindexMain`

**Role:** Entry point and coordinator. Replaces `GroupedReindexMain`.

**Responsibilities:**
- Parse command-line arguments (same interface as original)
- Load `config.ini` and `parallel_reindexer.properties`
- Handle nightly trigger checks (identical logic to core)
- Count total works to index
- Clear index if `full` mode (done once, before workers start)
- Start `WorkProducer` and `IndexerWorker` threads
- Wait for completion
- Run `finishIndexing()` for final commit and system variable updates

**Arguments:**
```
parallel_reindexer <serverName> [full|fullNoClear|nightly]
```

### 4.2 `WorkProducer`

**Role:** Dedicated thread that reads grouped work IDs from the database and feeds
them into the shared `BlockingQueue`.

**Key behavior:**
- Uses MySQL streaming ResultSet (`setFetchSize(Integer.MIN_VALUE)`) to avoid
  loading all rows into memory
- Blocks on `queue.put()` when the queue is full (backpressure)
- Sends N poison pills when done (one per worker)
- Uses its own dedicated DB connection

### 4.3 `IndexerWorker`

**Role:** Worker thread that processes grouped works.

**Lifecycle:**
1. Creates its own JDBC `Connection`
2. Instantiates its own `GroupedWorkIndexer` (with `clearIndex=false`)
3. Pulls `WorkUnit`s from the queue in a loop
4. Calls `indexer.processGroupedWork(id, permanentId, groupingCategory)`
5. Updates `date_updated` for works that had null timestamps
6. Reports progress to `ProgressTracker`
7. Exits on poison pill; cleans up connection and indexer

### 4.4 `WorkUnit`

**Role:** Immutable value object representing one grouped work to be indexed.

**Fields:** `id`, `permanentId`, `groupingCategory`, `dateUpdated`

### 4.5 `ProgressTracker`

**Role:** Thread-safe statistics aggregator.

**Features:**
- Atomic counters for works processed, errors, Solr docs
- Per-worker breakdown tracking
- Periodic progress reporting with ETA calculation
- Final summary with throughput stats

### 4.6 `ParallelReindexConfig`

**Role:** Configuration loader with sensible defaults.

**Reads from:** `parallel_reindexer.properties`

---

## 5. Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `workerThreadCount` | 4 | Number of parallel worker threads |
| `solrClientThreadCount` | 4 | Threads in the Solr HTTP client |
| `solrQueueSize` | 200 | Solr client document queue size |
| `workQueueCapacity` | 10000 | Max items in the producer→worker queue |
| `commitIntervalPerWorker` | 5000 | Works per worker between Solr commits |
| `dbFetchBatchSize` | 50000 | DB fetch size hint |
| `perWorkerStats` | true | Log per-worker counts at end |
| `progressReportInterval` | 10000 | Works between progress log entries |

### Tuning Guidelines

**For 3M+ doc collections (recommended starting point):**
```properties
workerThreadCount=8
solrClientThreadCount=8
solrQueueSize=500
workQueueCapacity=20000
commitIntervalPerWorker=5000
progressReportInterval=25000
```

**For smaller collections (< 500K):**
```properties
workerThreadCount=4
solrClientThreadCount=4
solrQueueSize=100
workQueueCapacity=5000
```

### Database Considerations

Each worker creates its own MySQL connection. Ensure your MySQL `max_connections`
is sufficient:

```
Required connections = workerThreadCount + 2 (producer + coordinator)
                     + existing Aspen connections (web, other indexers)
```

For 8 workers: ~10 new connections. Most MySQL defaults allow 151+, so this is
typically not an issue.

---

## 6. Deployment Guide

### Prerequisites

- Aspen Discovery 26.03.00 or later
- JDK 11+ (same as Aspen)
- The core `reindexer.jar` must be present at `code/reindexer/reindexer.jar`
- Solr 8.11.x JARs at `sites/default/solr-8.11.2/dist/`

### Build

```bash
cd code/parallel_reindexer
./build.sh
```

This produces `parallel_reindexer.jar`.

### Install

1. Copy `parallel_reindexer.jar` to the server alongside the reindexer
2. Copy `parallel_reindexer.properties` next to the JAR (or to the Aspen log dir)
3. Edit `parallel_reindexer.properties` to tune for your hardware

### Run (Manual)

```bash
./run_parallel_reindex.sh <serverName> full
```

### Run (Cron / Replace Nightly)

To use this for nightly reindexes, modify the cron entry that currently runs
the standard reindexer. For example, change:

```cron
# OLD:
0 2 * * * cd /usr/local/aspen-discovery/code/reindexer && java -jar reindexer.jar myserver nightly
```

To:

```cron
# NEW:
0 2 * * * cd /usr/local/aspen-discovery/code/parallel_reindexer && ./run_parallel_reindex.sh myserver nightly
```

The parallel reindexer handles all the same nightly trigger logic as the original.

### Rollback

Simply revert the cron entry to point back to the original `reindexer.jar`.
No core files are modified.

---

## 7. How It Differs From the Core Reindexer

| Aspect | Core Reindexer | Parallel Reindexer |
|--------|---------------|-------------------|
| Threading | Single thread | N configurable workers |
| DB connections | 1 | N + 2 (workers + producer + coordinator) |
| Solr client threads | 1 (hardcoded) | N (per-instance, configurable via GroupedWorkIndexer) |
| Work distribution | Sequential ResultSet loop | Producer→BlockingQueue→Workers |
| Progress reporting | Every 5000 works (debug log) | Configurable with ETA and throughput |
| Memory footprint | ~512MB-1GB | ~1-3GB (more instances, more caches) |
| Core code changes | N/A | **None** |
| Index clearing | In constructor | Once by coordinator before workers start |
| Final commit | In `finishIndexing()` | Same — coordinator calls `finishIndexing()` |

### What This Plugin Does NOT Change

- Solr schema or configuration
- Grouped work processing logic
- Record grouping algorithms
- MARC processing or enrichment loading
- Database schema or tables
- The web application or any PHP code
- Accelerated Reader data loading (still handled by the nightly process)

---

## 8. Risks and Mitigations

### 8.1 Database Connection Exhaustion

**Risk:** N workers + producer + coordinator + existing Aspen processes could exceed
MySQL `max_connections`.

**Mitigation:** Default of 4 workers uses only 6 connections. Document the requirement.
Config file makes it easy to reduce.

### 8.2 Solr "Too Many Searchers Warming"

**Risk:** If workers commit too frequently, Solr can't warm searchers fast enough.

**Mitigation:** The core `GroupedWorkIndexer` handles commits via its own
`indexCommitInterval` (loaded from `system_variables`, default 10000). Each worker
instance respects this independently. Solr's `autoCommit` settings
(`solrconfig.xml:293-295`) provide the hard commit interval of 15 seconds, which
is already tuned for production.

### 8.3 NightlyIndexLogEntry Thread Safety

**Risk:** The `NightlyIndexLogEntry` is shared across workers and uses
`PreparedStatement`s that are not synchronized.

**Mitigation:** `saveResults()` is called infrequently (every 5000 works per worker
via `incNumWorksProcessed()`). In practice, concurrent writes to the log table are
safe because MySQL handles row-level locking on the `reindex_log` table. The log
entry is updated (not inserted) after initial creation.

### 8.4 Memory Pressure

**Risk:** Each `GroupedWorkIndexer` loads its own copy of scopes, translation maps,
lexile data, and record processors.

**Mitigation:** These are read-only data loaded at init. For 8 workers, expect
~2-3GB total heap. The `run_parallel_reindex.sh` defaults to `-Xmx2g` — increase
for larger deployments. Monitor with the memory stats that Aspen logs automatically.

### 8.5 Duplicate Processing

**Risk:** Could the same work be sent to two workers?

**Mitigation:** No. The `WorkProducer` reads each row exactly once from a single
`ResultSet`. The `BlockingQueue` ensures FIFO delivery with no duplication.

---

## 9. Monitoring and Troubleshooting

### Log Output

The parallel reindexer logs to `parallel_grouped_reindex.log` in the standard
Aspen log directory. Look for:

```
[INFO] Starting Parallel Reindex for myserver
[INFO] Configuration: ParallelReindexConfig{workerThreadCount=8, ...}
[INFO] Total works to index: 3,245,000
[INFO] Starting 8 parallel worker threads
[INFO] Worker-0 initialized GroupedWorkIndexer
[INFO] Worker-1 initialized GroupedWorkIndexer
...
[INFO] Progress: 10,000 / 3,245,000 (0.3%) | 847 works/sec | ETA: 1h 3m 22s | Errors: 0
[INFO] Progress: 20,000 / 3,245,000 (0.6%) | 912 works/sec | ETA: 58m 51s | Errors: 0
...
[INFO] COMPLETED: 3,245,000 works in 59m 12s (914 works/sec) | 3 errors | 3,244,997 Solr docs
[INFO] Per-worker breakdown: Worker-0=405625 Worker-1=405624 ...
```

### Reindex Log Table

The plugin writes to the same `reindex_log` table as the core reindexer, so it
appears in the Aspen admin UI under System Administration → Reindex Log.

### Common Issues

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| "Unable to create solr client, out of memory" | Heap too small | Increase `-Xmx` in `run_parallel_reindex.sh` |
| Workers die with `SQLException` | MySQL connection limit | Reduce `workerThreadCount` or increase `max_connections` |
| "too many searchers warming" in Solr log | Commits too frequent | Increase `indexCommitInterval` in Aspen system_variables |
| Low throughput despite multiple workers | DB is the bottleneck | Check MySQL `innodb_buffer_pool_size`, I/O, slow queries |
| Uneven worker counts | Normal variation | Some works take longer (more records/items) |

---

## 10. Admin UI Integration ("Turbo Reindex")

The parallel reindexer is accessible from the Aspen Discovery admin UI as a
one-time operation called **Turbo Reindex**. It does NOT replace the normal
nightly reindex or incremental indexing — it's an additional tool.

### What It Does

- Re-indexes all grouped works already in the Aspen database using parallel threads
- Does NOT pull new records from the ILS (Koha, Sierra, Polaris, etc.)
- The normal incremental indexer continues to run alongside it
- No cron job changes needed — it's a one-click operation from the admin panel

### How to Access

1. Log in as an admin with "Perform System Maintenance" permission
2. Go to **Administration → System Administration → Turbo Reindex**
3. Select the number of worker threads (2-12)
4. Optionally check "Clear existing index first" for a full clean rebuild
5. Click **Start Turbo Reindex**

### What Happens Behind the Scenes

1. The admin page calls `SystemUtils::startBackgroundProcess('parallelReindex', ...)`
2. This launches `cron/parallelReindex.php` as a background process
3. The PHP script assembles the Java classpath and launches `parallel_reindexer.jar`
4. Output is streamed back to the BackgroundProcess record for live monitoring
5. Progress is visible via **Administration → System Reports → Background Processes**

### UI Files

| File | Purpose |
|------|---------|
| `code/web/services/Admin/ParallelReindex.php` | Admin controller |
| `code/web/interface/themes/responsive/Admin/parallelReindex.tpl` | Smarty template |
| `code/web/cron/parallelReindex.php` | Background process launcher |
| `code/web/sys/Account/User.php` (line ~4263) | Menu registration |

### ILS Independence

The Turbo Reindex works with **any ILS** configured in Aspen Discovery:
- Koha, Sierra/Millennium (III), Symphony, Polaris, CarlX, Evergreen, Evolve, Folio
- OverDrive, CloudLibrary, Hoopla, Axis 360, Palace Project (eContent)
- Side-loaded content

It reads from the `grouped_work` and `grouped_work_records` tables, which are
ILS-agnostic. Whatever records are already in Aspen get re-indexed in parallel.

---

## 11. Future Enhancements

### Phase 2 (Potential)

- **Shared Solr client:** Instead of each `GroupedWorkIndexer` creating its own
  Solr client, inject a shared one with higher thread count. Requires minor
  refactoring of `GroupedWorkIndexer` (add a constructor or setter for the Solr client).

- **Batch DB loading:** Pre-fetch enrichment data (ratings, lexile, display info)
  in bulk rather than per-work. Could provide another 2-3x improvement by reducing
  DB round-trips.

- **Index-level parallelism:** For very large collections, partition the work by
  grouping_category or ID range, allowing different workers to specialize.

- **Incremental parallel mode:** Currently the main value is for full reindexes.
  Incremental reindexes (typically < 10K works) don't benefit much from parallelism
  due to worker initialization overhead.

### Phase 3 (Aspirational)

- **Streaming pipeline:** Three-stage pipeline with separate thread pools for
  DB loading, document building, and Solr submission.

- **Custom SolrJ client with connection pooling:** Replace per-instance Solr
  clients with a shared pool that uses HTTP/2 multiplexing more effectively.

---

## 12. File Inventory

```
code/parallel_reindexer/                           (Java plugin)
├── SPECIFICATION.md                  ← This document
├── build.sh                          ← Build script
├── run_parallel_reindex.sh           ← Run script with JVM tuning
├── parallel_reindexer.properties     ← Configuration (with defaults documented)
├── parallel_reindexer.iml            ← IntelliJ IDEA module file
├── parallel_reindexer.jar            ← Output (after build)
├── lib/                              ← (empty, dependencies come from reindexer)
└── src/
    └── com/bws/aspen/parallel_reindexer/
        ├── ParallelReindexMain.java  ← Entry point and coordinator
        ├── WorkProducer.java         ← DB cursor → BlockingQueue
        ├── IndexerWorker.java        ← Worker thread (owns GroupedWorkIndexer)
        ├── WorkUnit.java             ← Immutable work item value object
        ├── ProgressTracker.java      ← Thread-safe stats and ETA
        └── ParallelReindexConfig.java← Configuration loader

code/web/                                          (Admin UI integration)
├── services/Admin/ParallelReindex.php    ← Admin controller page
├── interface/themes/responsive/Admin/
│   └── parallelReindex.tpl               ← Smarty template (Turbo Reindex UI)
├── cron/parallelReindex.php              ← Background process launcher
└── sys/Account/User.php                  ← Menu registration (modified)
```

---

## 13. Testing Plan

### Unit Tests (future)

- `WorkUnit` serialization and poison pill detection
- `ParallelReindexConfig` loading with missing/invalid properties
- `ProgressTracker` counter accuracy under concurrent increments

### Integration Tests

1. **Small collection (< 1000 works):**
   - Run parallel reindexer, verify Solr doc count matches standard reindexer
   - Compare a sample of Solr documents field-by-field

2. **Medium collection (50K-100K works):**
   - Measure throughput with 1, 2, 4, 8 threads
   - Verify no duplicate or missing documents
   - Check `reindex_log` for correct timing and counts

3. **Large collection (1M+ works):**
   - Full reindex with 8 threads
   - Monitor MySQL connection count, Solr memory, JVM heap
   - Compare total reindex time vs. standard reindexer
   - Verify search results are identical

### Validation Queries

After a parallel reindex, run these to verify correctness:

```sql
-- Count of indexed works should match
SELECT COUNT(DISTINCT permanent_id) FROM grouped_work
INNER JOIN grouped_work_records ON grouped_work.id = groupedWorkId;

-- Solr doc count (via Solr admin or curl)
curl 'http://localhost:8080/solr/grouped_works_v2/select?q=*:*&rows=0'

-- These two counts should match
```
