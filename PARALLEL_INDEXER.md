# Parallel / Threaded Indexer for Aspen Discovery

**Branch:** `feature/parallel-indexer-26.10` · **Base:** `26.10.00` · ByWater Solutions

This branch adds **multi-threaded reindexing** to Aspen Discovery. The stock reindexer
processes grouped works in a single thread with a single DB connection and a single-threaded
Solr client — leaving CPU, DB, and Solr capacity idle. For large collections a full reindex
can take many hours. This work parallelizes both the **gather** (reading work data) and the
**index** (writing to Solr) stages.

It ships as **two complementary approaches** so you can choose minimal risk or maximum speed.

---

## Approach 1 — Threaded Reindexer (minimal, surgical)

A small patch to the **stock reindexer**, gated entirely by configuration. This is the
low-risk option and the recommended starting point.

- `GroupedReindexMain.processGroupedWorksThreaded()` — a producer/consumer model: one
  producer thread streams grouped-work IDs into a bounded `BlockingQueue`; **N worker
  threads** each pull from it, every worker with its own DB connection and its own
  `GroupedWorkIndexer` instance (own Solr client, prepared statements, caches).
- Activates **only for full reindex** (`fullNoClear` / `nightly`). Incremental reindexes stay
  single-threaded.
- **Fully reversible without swapping JARs** — set `numReindexWorkerThreads = 1` to get
  exact stock behavior.

**Files touched (additive):**
- `code/reindexer/src/org/aspen_discovery/reindexer/GroupedReindexMain.java`
- `code/reindexer/src/org/aspen_discovery/reindexer/GroupedWorkIndexer.java`

**Benchmark (prior run, ~219K works):** 339 s · ~645 works/sec · 0 errors.

---

## Approach 2 — `parallel_reindexer` module (standalone, maximum throughput)

A **separate module** in `code/parallel_reindexer/` with **zero core modifications** — it
instantiates multiple `GroupedWorkIndexer` copies and also parallelizes the **Koha bulk
extract** (the data-gather side). Targets a **4–6× throughput improvement** on large
collections.

- `ParallelReindexMain` — coordinator + worker pool for re-indexing works already in Aspen.
- `TurboMigrationMain` + `KohaBulkExtractor` + `BulkLoadWorker` — multi-threaded bulk extract
  from Koha into the Aspen DB (migration / first load).
- `WorkProducer`, `IndexerWorker`, `WorkUnit`, `ProgressTracker`, `ParallelReindexConfig`.
- PHP integration: `code/web/cron/parallelReindex.php`, `code/web/cron/turboMigration.php`,
  `code/web/services/API/TurboAPI.php`, `code/web/services/Admin/ParallelReindex.php`, and the
  "Turbo Reindex" admin page.

See the in-repo docs for the deep dive:
- `code/parallel_reindexer/SPECIFICATION.md` — full architecture & thread-safety analysis
- `code/parallel_reindexer/THREADED_REINDEXER.md` — approach 1 details & benchmark
- `code/parallel_reindexer/INTELLIJ_BUILD_GUIDE.md` — build in IntelliJ
- `code/parallel_reindexer/CERULEAN_API_GUIDE.md`, `PHP_FPM_VS_MOD_PHP.md`

---

## Configuration

All knobs live in the `system_variables` table
(**System Administration → System Variables** in the Aspen admin UI):

| Setting | Stock | Suggested | Effect |
|---|---|---|---|
| `solrThreadCount` | 1 | 4 | Solr HTTP client threads per indexer instance *(landed upstream in 26.06)* |
| `solrQueueSize` | 25 | 200 | Solr document buffer before blocking *(landed upstream in 26.06)* |
| `numReindexWorkerThreads` | 1 | 4 | **New** — parallel indexing workers for full reindex |

`numReindexWorkerThreads` is added via the `26.10.00` DB-maintenance migration
(`add_parallel_reindex_worker_threads`) and exposed in `SystemVariables.php` — it applies
automatically on the normal Aspen database update. Set it back to `1` to disable threading.

> Memory scales with worker count (~300–500 MB per worker). Size threads to your DB and Solr
> headroom, not just CPU cores.

---

## Build

Both modules compile cleanly against `26.10.00`. The prebuilt `reindexer.jar` and
`parallel_reindexer.jar` on this branch were rebuilt from the recompiled 26.10 classes.

- **Reindexer** — build the `reindexer` artifact in IntelliJ as usual, or patch the two
  recompiled classes into the stock JAR (see `THREADED_REINDEXER.md` → *Build Instructions*).
- **parallel_reindexer** — `cd code/parallel_reindexer && ./build.sh` (produces
  `parallel_reindexer.jar`, entry point `com.bws.aspen.parallel_reindexer.ParallelReindexMain`).

## Rollback

```sql
-- Disable threading entirely (no JAR swap needed):
UPDATE system_variables SET numReindexWorkerThreads = 1;
```

---

## Provenance

Rebased from `backup/parallel-indexer-snapshot` (the pre-26.10 WIP, preserved as a tag on this
repo) onto current `26.10.00`. During the 6-month gap, `solrThreadCount`/`solrQueueSize`
landed upstream (26.06), so only `numReindexWorkerThreads` and the threaded reindex path
remain unique to this branch. Not intended for the upstream Aspen repo — this is a BWS dev
branch for review and testing.
