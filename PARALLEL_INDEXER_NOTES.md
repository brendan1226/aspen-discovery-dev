# Parallel / Threaded Indexer — Design Notes, Rationale & Benchmarking Guide

**Audience:** engineer reviewing, benchmarking, and studying this branch.
**Branch:** `feature/parallel-indexer-26.10` (rebased onto `26.10.00`).
**Companion docs:** `PARALLEL_INDEXER.md` (quick overview), plus the deep-dive docs in
`code/parallel_reindexer/` (`SPECIFICATION.md`, `THREADED_REINDEXER.md`,
`INTELLIJ_BUILD_GUIDE.md`, `CERULEAN_API_GUIDE.md`, `PHP_FPM_VS_MOD_PHP.md`).

This document explains **what the code does, where it changed, and — most importantly — why
each design decision was made**, so you can evaluate the trade-offs and benchmark it fairly.

---

## 1. The problem

The stock Aspen reindexer (`GroupedReindexMain` → `GroupedWorkIndexer.processGroupedWorks()`)
processes grouped works in a **strictly sequential loop** on a single thread, with:

- one DB connection,
- one `GroupedWorkIndexer` instance whose `processGroupedWork(...)` is `synchronized`,
- a Solr client historically pinned to `threadCount=1, queueSize=25`.

Each work triggers ~5–10 DB queries plus enrichment lookups, then a Solr document add. On a
multi-core box with a capable DB and Solr, **the cores sit idle** — throughput is bounded by
the serial per-work latency, not by hardware. For 1M–5M+ work collections this means multi-hour
reindexes.

---

## 2. Two approaches, and why both exist

We deliberately built **two** implementations with different risk/reward profiles. A reviewer
should understand *why*, because it affects what you benchmark.

### Approach A — Threaded Reindexer (in-core patch)
Small, additive change **inside** the stock reindexer. Adds a producer/consumer path to
`GroupedReindexMain` and three config fields to `GroupedWorkIndexer`.

- **Why we built it:** lowest-risk path to a real speedup. It reuses the exact code paths the
  nightly index already uses, is gated by a single config value, and reverts to stock behavior
  with `numReindexWorkerThreads = 1` — no JAR swap, no separate deploy. Easiest to get comfortable
  with in production and the most plausible upstream contribution.
- **Trade-off:** it modifies core files, so it must be rebuilt into `reindexer.jar` and carried
  as a patch until/unless upstreamed.

### Approach B — `parallel_reindexer` (standalone plugin module)
A separate module (`code/parallel_reindexer/`, package `com.bws.aspen.parallel_reindexer`) that
**does not modify any core file**. It instantiates multiple `GroupedWorkIndexer` copies and
reaches the package-private `processGroupedWork(...)` via reflection. It also adds a
**Koha bulk-extract / migration** path (the "gather" side) that the in-core patch does not.

- **Why we built it:** maximum throughput and a clean separation from core (ships as its own
  JAR, updated independently of Aspen releases). It also tackles first-load/migration, where the
  bottleneck is extracting from Koha, not just indexing.
- **Trade-off:** relies on reflection against package-private methods (`processGroupedWork`,
  `finishIndexing`), so it is **coupled to internal method signatures** and can break on an Aspen
  upgrade. It is the more experimental of the two.

**Bottom line for benchmarking:** Approach A is the "ship it" candidate; Approach B is the
"how fast can we theoretically go + migration" candidate. Compare both against stock.

---

## 3. Core design decisions and the rationale

These are the decisions worth scrutinizing. Each lists the **why** and the **alternative we
rejected**.

### 3.1 One `GroupedWorkIndexer` per worker thread (not one shared, thread-safe indexer)
- **Why:** `GroupedWorkIndexer` holds per-instance `PreparedStatement`s and caches, and its
  `processGroupedWork(...)` is `synchronized`. Making a single instance safe for concurrent use
  would mean a deep refactor of a large, central class (high risk of subtle indexing bugs).
  Instead each worker gets its **own** indexer + **own** JDBC connection, so there is no shared
  mutable state on the hot path.
- **Rejected alternative:** refactor `GroupedWorkIndexer` to be reentrant/thread-safe. Too
  invasive; would be very hard to prove correct against stock output.
- **Cost to measure:** ~300–500 MB heap **per worker** (each has its own statements + caches).
  This is the main memory consideration when sizing threads — see benchmarking.

### 3.2 Producer/consumer with a bounded `BlockingQueue`
- **Why:** decouples "reading work IDs from the DB" from "processing works." A single producer
  streams IDs; N workers consume. The **bounded** queue (10,000) gives natural **backpressure** —
  if workers fall behind, the producer blocks (`queue.put`); if the DB is slow, workers block
  (`queue.take`). No unbounded memory growth, no busy-waiting.
- **Rejected alternative:** partition the ID range and give each worker a slice up front. Simpler,
  but suffers from skew (some ranges are heavier) and loses the self-balancing property.

### 3.3 Streaming MySQL ResultSet (`setFetchSize(Integer.MIN_VALUE)`)
- **Why:** a full reindex enumerates every grouped work (millions of rows). Default JDBC behavior
  buffers the entire ResultSet in memory. `Integer.MIN_VALUE` switches the MySQL connector into
  row-by-row streaming so the producer uses near-constant memory regardless of collection size.
- **Caveat to study:** a streaming ResultSet holds its connection busy for the whole scan — that
  is exactly why the producer uses its **own dedicated connection**, separate from the workers.

### 3.4 Clear the index once, in the coordinator — never in a worker
- **Why:** clearing Solr is a one-time, destructive operation. If workers cleared, they would race
  and could wipe each other's freshly-indexed docs. In both approaches the coordinator clears the
  index **before** any worker starts (Approach B: Phase 2; the `IndexerWorker` constructor hard-codes
  `clearIndex = false`). Workers only ever add.

### 3.5 Threading only for **full** reindex; incremental stays single-threaded
- **Why:** full reindex is the expensive, parallelizable case and is self-contained. Incremental /
  continuous indexing interleaves with live updates, scheduled works, and deletions where ordering
  and the existing `synchronized` guarantees matter. Keeping incremental on the stock path avoids a
  whole class of concurrency questions for little benefit (incremental batches are small).
- **Where enforced (Approach A):** `GroupedReindexMain` only calls the threaded path when
  `numReindexWorkerThreads > 1 && fullReindex`.

### 3.6 Config-gated, defaults to stock (`numReindexWorkerThreads = 1`)
- **Why:** safety and reversibility. The feature ships **off**. Turning it on is one admin setting;
  turning it back off needs no redeploy. This makes production rollout and A/B benchmarking trivial.

### 3.7 Solr client made configurable (`solrThreadCount`, `solrQueueSize`)
- **Why:** with N workers each adding documents, a 1-thread/25-doc Solr client becomes the new
  bottleneck. These are now driven from `system_variables`.
- **Note:** these two landed **upstream in 26.06** independently, so on 26.10 they already exist —
  only `numReindexWorkerThreads` and the threaded reindex path are unique to this branch. (Good
  sign: the upstream project is moving the same direction.)

### 3.8 Reflection to reach `processGroupedWork` / `finishIndexing` (Approach B only)
- **Why:** those methods are package-private. Reflection lets the plugin call them **without
  modifying core Aspen** — preserving the "zero core changes, ships as its own JAR" property.
- **Explicit trade-off to evaluate:** this couples the plugin to internal signatures
  (`processGroupedWork(Long, String, String)`), so an Aspen upgrade can break it at runtime. Approach
  A avoids reflection entirely by being in-core. If you dislike reflection, prefer Approach A.

### 3.9 Koha bulk extract: one streaming JOIN instead of ~940k point SELECTs (Approach B migration)
- **Why:** on first load/migration the dominant cost is pulling bibs+items out of Koha. The stock
  path issues per-bib queries. `KohaBulkExtractor` runs **one** streaming `biblio_metadata ⨝ items
  ⨝ issues` query, assembles MARC (with 952 item fields matching `KohaExportMain.updateBibRecord`'s
  subfield mapping), and feeds batches to parallel loaders. This is the "multi-thread the gather"
  half of the original goal.
- **What to verify:** that the assembled 952 mapping matches your Koha's item schema/columns — the
  column list is explicit in `KohaBulkExtractor.extractAll()` and should be diffed against the Koha
  version you benchmark on.

---

## 4. Where the code changed (study map)

### Approach A — in-core (the diff vs stock 26.10)
| File | Change | What to read |
|---|---|---|
| `code/reindexer/src/org/aspen_discovery/reindexer/GroupedReindexMain.java` | +imports; routing hook in `main()`; new `processGroupedWorksThreaded()` | The whole parallel path — producer thread, worker pool, poison pills, progress logging, graceful join + exception collection |
| `code/reindexer/src/org/aspen_discovery/reindexer/GroupedWorkIndexer.java` | +`numReindexWorkerThreads` field, read from `system_variables`, +getter | `solrThreadCount`/`solrQueueSize` were already present (upstream 26.06); we only add the worker-thread knob |
| `code/web/sys/DBMaintenance/version_updates/26.10.00.php` | migration `add_parallel_reindex_worker_threads` | Adds the column the 26.10-native way (not `aspen.sql`) |
| `code/web/sys/SystemVariables.php` | property + admin form field | Exposes the knob in the admin UI (min 1, max 16) |
| `code/web/sys/Account/User.php` | "Turbo Reindex" admin menu action | Links the Approach-B admin page |

> Note on integration path: 26.06 added `solrThreadCount`/`solrQueueSize` via a DB-maintenance
> migration + `SystemVariables.php`, **not** by editing `aspen.sql`. We followed that exact pattern
> for `numReindexWorkerThreads`. The original pre-26.10 snapshot edited `aspen.sql` directly; that
> approach is obsolete on 26.10 and was intentionally dropped.

### Approach B — standalone module (all new, `code/parallel_reindexer/`)
| Class | Role |
|---|---|
| `ParallelReindexMain` | Coordinator: count works → (optional) clear index once → start producer + worker pool → await (48h cap) → collect worker exceptions → final commit via `finishIndexing` (reflection) |
| `WorkProducer` | Dedicated thread; streams work IDs (`setFetchSize(MIN_VALUE)`) into the queue; emits one poison pill per worker when done |
| `IndexerWorker` | One per thread; own DB conn + own `GroupedWorkIndexer`; `take()` loop; calls `processGroupedWork` via reflection; records progress/errors |
| `WorkUnit` | Immutable work item (id, permanentId, groupingCategory, dateUpdated) + `POISON_PILL` sentinel |
| `ProgressTracker` | Thread-safe (`AtomicLong`/`ConcurrentHashMap`) throughput + error stats |
| `ParallelReindexConfig` | Loads `parallel_reindexer.properties`; defaults: 4 workers, Solr 4/200, queue 10k, commit 5k/worker |
| `TurboMigrationMain` | Migration entry point: load Koha conn from `account_profiles` → count bibs → extractor + N loaders |
| `KohaBulkExtractor` | One streaming Koha JOIN → assembles MARC w/ 952 item fields → batches into queue |
| `BulkLoadWorker` | Consumes MARC batches; loads + groups into Aspen DB |
| `MarcBatch` / `MigrationProgressTracker` | Batch container + migration stats |
| PHP: `cron/parallelReindex.php`, `cron/turboMigration.php`, `services/API/TurboAPI.php`, `services/Admin/ParallelReindex.php`, `interface/.../Admin/parallelReindex.tpl` | Cron entry points, API, and admin UI |

---

## 5. Benchmarking guide

### 5.1 What to measure
- **Wall-clock** for a full reindex (the number that matters operationally).
- **Throughput** (works/sec) — both tools log this; also derive it yourself from log timestamps.
- **Error count** — must stay at/near 0; a fast reindex that drops docs is not a win.
- **Solr doc count** after each run — compare across configs and **against the stock run** to
  prove correctness (same input ⇒ same doc count).
- **Resource use during the run:** CPU per core, JVM heap (watch GC), MySQL load / active threads,
  Solr CPU and merge activity.

### 5.2 Controlled method (important for fair numbers)
1. **Baseline first:** stock single-threaded (`numReindexWorkerThreads = 1`) on the target data.
   Record wall-clock, works/sec, and final Solr doc count. This is your reference.
2. **Fixed dataset:** same DB snapshot for every run. Restore between runs so caches/state don't
   drift. Re-clear the index each run (full reindex) so you compare like with like.
3. **Sweep one variable at a time.** Suggested `numReindexWorkerThreads` sweep: 1 → 2 → 4 → 8 → 12 →
   16. Expect sub-linear scaling and a knee where DB or Solr saturates.
4. **Then tune Solr:** at your best worker count, sweep `solrThreadCount` (e.g. 1,2,4) and
   `solrQueueSize` (25,100,200,400). The Solr client is a common second bottleneck.
5. **Repeat each config** at least twice; take the median. Discard the first "cold" run or note it.
6. **Heap:** size `-Xmx` to roughly `(workers × ~400 MB) + headroom` and record it per run — an
   under-sized heap shows up as GC thrash, not a clean slowdown.

### 5.3 Where the bottleneck usually moves
As you add workers the limiter typically shifts: **CPU → MySQL → Solr**. Watch all three. If
works/sec plateaus while CPU is idle, the DB or Solr is the wall — adding workers past that point
just adds memory pressure. Record *which* resource saturated at the knee; that's the real finding.

### 5.4 Correctness checks (do not skip)
- Final **Solr doc count** matches the stock baseline (±expected deletions).
- Spot-check a sample of grouped works in Solr for identical field content vs a stock-indexed copy.
- Confirm `date_updated` is being set (workers update it when it was null) so subsequent
  incremental indexes behave.
- Run with `numReindexWorkerThreads = 1` and confirm it is byte-for-byte the stock code path.

### 5.5 Approach B (migration) specifics
- Validate the Koha JOIN/952 mapping against your Koha schema **before** trusting throughput
  numbers — wrong mapping = fast but wrong.
- Benchmark `TurboMigrationMain <server> [workers] [batchSize] [profile]` by sweeping `workers`
  and `batchSize` (default 500); compare total migrate+index time vs the stock export+index.

---

## 6. Known risks / things to scrutinize
- **Approach B reflection** couples to package-private signatures; verify at runtime on your Aspen
  version (this branch compiles & the signatures match 26.10).
- **Shared `logEntry`** across workers: writes are frequent (notes/counters) and infrequent-saves;
  low risk but worth a look under high thread counts (`NightlyIndexLogEntry`).
- **DB connection count** = workers + producer (+ coordinator). Ensure MySQL `max_connections` and
  pool limits accommodate your chosen thread count.
- **Memory** scales with workers; size `-Xmx` deliberately.
- **Solr commit pressure** rises with throughput; `solrQueueSize` and commit intervals interact —
  tune together.

---

## 7. Provenance & safety
Rebased from the pre-26.10 WIP (`backup/parallel-indexer-snapshot`, preserved as a tag on this
repo) onto current `26.10.00`. Both modules compile cleanly against 26.10; `reindexer.jar` and
`parallel_reindexer.jar` were rebuilt from the recompiled classes. This is a **BWS dev branch for
review and benchmarking — not intended for the upstream Aspen repo.**
