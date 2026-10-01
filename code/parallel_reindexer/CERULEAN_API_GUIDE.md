# Turbo Tools — API Guide for Cerulean Integration

**Version:** 1.0.0  
**Date:** 2026-04-07  
**Base URL:** `http://<aspen-host>:<port>/API/TurboAPI`

---

## Overview

These API endpoints allow Cerulean to orchestrate the full migration pipeline:

1. **Push bibs into Koha** (handled by Cerulean / migration platform)
2. **Trigger Turbo Migration** → bulk extract from Koha into Aspen DB
3. **Poll for status** → get real-time progress with detailed notes
4. **Trigger Turbo Reindex** → parallel index Aspen DB into Solr
5. **Verify counts** → confirm everything landed correctly

```
Cerulean                        Aspen Discovery
────────                        ───────────────
  │
  │  1. Ingest bibs into Koha
  │     (via migration platform)
  │
  │  2. GET /API/TurboAPI?method=getTurboCounts
  │ ──────────────────────────────────────────────▶  Returns Koha bib/item counts
  │ ◀──────────────────────────────────────────────  + Aspen record counts
  │
  │  3. GET /API/TurboAPI?method=startTurboMigration&workerThreads=4
  │ ──────────────────────────────────────────────▶  Starts bulk Koha → Aspen DB
  │ ◀──────────────────────────────────────────────  Returns backgroundProcessId
  │
  │  4. Poll: GET /API/TurboAPI?method=getTurboStatus&backgroundProcessId=123
  │ ──────────────────────────────────────────────▶  Returns status + progress notes
  │ ◀──────────────────────────────────────────────  (repeat until isRunning=false)
  │
  │  5. GET /API/TurboAPI?method=startTurboReindex&workerThreads=8
  │ ──────────────────────────────────────────────▶  Starts parallel Solr indexing
  │ ◀──────────────────────────────────────────────  Returns backgroundProcessId
  │
  │  6. Poll: GET /API/TurboAPI?method=getTurboStatus&backgroundProcessId=124
  │ ──────────────────────────────────────────────▶  Returns status + progress notes
  │ ◀──────────────────────────────────────────────  (repeat until isRunning=false)
  │
  │  7. GET /API/TurboAPI?method=getTurboCounts
  │ ──────────────────────────────────────────────▶  Verify: Koha bibs = Aspen records
  │ ◀──────────────────────────────────────────────
```

---

## Authentication

The API uses **IP-based access control**. Configure Cerulean's IP in the Aspen admin:

**Admin → System Administration → IP Addresses** → Add/edit the IP → enable **"Allow API Access"**

No tokens or API keys needed for IP-whitelisted clients.

---

## Endpoints

### 0. `stopAspenIndexers` / `startAspenIndexers` — Resource Control

**IMPORTANT:** Call `stopAspenIndexers` before starting any Turbo operation. Aspen runs
background indexers (koha_export, sideload_processing, user_list_indexer) that auto-restart
every 5 minutes via cron. These compete for CPU, memory, and database locks.

`stopAspenIndexers` kills all running Java indexer processes AND disables the cron that
restarts them. `startAspenIndexers` re-enables the cron and triggers an immediate restart.

The PHP launchers (`parallelReindex.php`, `turboMigration.php`) now do this automatically,
but calling it from Cerulean first gives you explicit control and confirmation.

**Stop indexers:**
```
GET /API/TurboAPI?method=stopAspenIndexers
```

**Response:**
```json
{
  "result": {
    "success": true,
    "message": "Indexers stopped and auto-restart disabled",
    "stopped": [
      "Cron auto-restart disabled",
      "Killed koha_export.jar (PID 1234)",
      "Killed sideload_processing.jar (PID 1235)",
      "Killed user_list_indexer.jar (PID 1236)"
    ],
    "errors": []
  }
}
```

**Re-enable indexers (after turbo operations complete):**
```
GET /API/TurboAPI?method=startAspenIndexers
```

**Response:**
```json
{
  "result": {
    "success": true,
    "message": "Auto-restart re-enabled, indexers will start within 5 minutes"
  }
}
```

**Note:** The Turbo Migration and Turbo Reindex PHP launchers now automatically call
stop/start, so this is also handled if you just call `startTurboMigration` directly.
But calling `stopAspenIndexers` first from Cerulean gives you the confirmation and
process list before kicking off the turbo operation.

---

### 1. `getTurboCounts` — Pre-flight / Verification

Returns record counts from both Koha and Aspen databases. Use before migration to
confirm Koha has the expected records, and after to verify they landed in Aspen.

**Request:**
```
GET /API/TurboAPI?method=getTurboCounts
```

**Response:**
```json
{
  "result": {
    "success": true,
    "aspen": {
      "ilsRecords": 0,
      "groupedWorks": 0
    },
    "koha": {
      "connected": true,
      "bibs": 470123,
      "items": 591456
    }
  }
}
```

**Fields:**
| Field | Type | Description |
|-------|------|-------------|
| `aspen.ilsRecords` | int | Non-deleted records in Aspen's `ils_records` table |
| `aspen.groupedWorks` | int | Total grouped works in Aspen |
| `koha.connected` | bool | Whether Aspen can reach the Koha database |
| `koha.bibs` | int | Total bibs in Koha's `biblio_metadata` table |
| `koha.items` | int | Total items in Koha's `items` table |

---

### 2. `startTurboMigration` — Bulk Extract Koha → Aspen DB

Starts a background process that bulk-extracts all records from Koha and loads them
into Aspen's database using parallel threads. Does NOT touch Solr.

**Request:**
```
GET /API/TurboAPI?method=startTurboMigration&workerThreads=4&batchSize=500&profileName=ils
```

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `workerThreads` | int | 4 | Parallel worker threads (1-16) |
| `batchSize` | int | 500 | Records per batch (100-5000) |
| `profileName` | string | "ils" | Indexing profile name |

**Response (success):**
```json
{
  "result": {
    "success": true,
    "message": "Turbo Migration started with 4 workers, batch=500",
    "backgroundProcessId": 123,
    "status": "started"
  }
}
```

**Response (already running):**
```json
{
  "result": {
    "success": false,
    "message": "Turbo Migration is already running",
    "backgroundProcessId": 122,
    "status": "running"
  }
}
```

---

### 3. `startTurboReindex` — Parallel Solr Indexing

Starts a background process that re-indexes all grouped works in Aspen's database
into Solr using parallel threads.

**Request:**
```
GET /API/TurboAPI?method=startTurboReindex&workerThreads=8&clearIndex=0
```

**Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `workerThreads` | int | 4 | Parallel worker threads (1-16) |
| `clearIndex` | 0/1 | 0 | Clear Solr index before reindexing (use with caution!) |

**Response (success):**
```json
{
  "result": {
    "success": true,
    "message": "Turbo Reindex started with 8 workers, mode=fullNoClear",
    "backgroundProcessId": 124,
    "status": "started"
  }
}
```

---

### 4. `getTurboStatus` — Poll for Progress

Returns the current status and detailed progress notes for a background process.
Poll this endpoint every 5-10 seconds while a migration or reindex is running.

**Request:**
```
GET /API/TurboAPI?method=getTurboStatus&backgroundProcessId=123
```

**Parameters:**
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `backgroundProcessId` | int | Yes | ID from startTurboMigration/startTurboReindex |

**Response (running):**
```json
{
  "result": {
    "success": true,
    "backgroundProcessId": 123,
    "name": "turboMigration",
    "status": "running",
    "isRunning": true,
    "startTime": 1712505600,
    "endTime": null,
    "elapsedSeconds": 342,
    "notes": "09:00:05 - Starting Turbo Migration: 4 workers, batch=500, profile=ils\n09:00:06 - Koha bib count: 470,123\n09:00:12 - Extract: 10,000 bibs (2.1%), 12,456 items | 1,852 bibs/sec\n09:01:45 - Load: 50,000 records upserted (100 batches) | 523 rec/sec\n09:02:10 - Group: 50,000 records grouped | 498 rec/sec"
  }
}
```

**Response (completed):**
```json
{
  "result": {
    "success": true,
    "backgroundProcessId": 123,
    "name": "turboMigration",
    "status": "completed",
    "isRunning": false,
    "startTime": 1712505600,
    "endTime": 1712507340,
    "elapsedSeconds": 1740,
    "notes": "...full log...\nMIGRATION COMPLETE: 470,123 bibs extracted (591,456 items), 470,123 loaded, 385,241 grouped in 29m 0s | 3 errors\nTurbo Migration completed successfully. Run Turbo Reindex to index into Solr."
  }
}
```

**Parsing the `notes` field:**

The notes field contains timestamped log lines. Key patterns to look for:

| Pattern | Meaning |
|---------|---------|
| `Extract: X bibs (Y%)` | Extraction progress with percentage |
| `Load: X records upserted` | Database loading progress |
| `Group: X records grouped` | Record grouping progress |
| `MIGRATION COMPLETE:` | Final summary line |
| `Progress: X / Y (Z%)` | Reindex progress (for Turbo Reindex) |
| `COMPLETED:` | Final reindex summary |
| `[ERR]` prefix | Error from Java stderr |

---

## Complete Cerulean Workflow Example

```python
import requests
import time

ASPEN_API = "http://aspen.example.com:85/API/TurboAPI"

def api_call(method, **params):
    params['method'] = method
    r = requests.get(ASPEN_API, params=params)
    return r.json()['result']

def wait_for_completion(process_id, poll_interval=10):
    """Poll until background process completes. Yields status updates."""
    while True:
        status = api_call('getTurboStatus', backgroundProcessId=process_id)
        yield status
        if not status['isRunning']:
            return
        time.sleep(poll_interval)

# ---- Step 0: Stop background indexers to free resources ----
result = api_call('stopAspenIndexers')
print(f"Stopped: {result.get('stopped', [])}")

# ---- Step 1: Check Koha has the records ----
counts = api_call('getTurboCounts')
print(f"Koha: {counts['koha']['bibs']} bibs, {counts['koha']['items']} items")
print(f"Aspen: {counts['aspen']['ilsRecords']} ILS records, {counts['aspen']['groupedWorks']} grouped works")
assert counts['koha']['connected'], "Koha not connected!"

# ---- Step 2: Start Turbo Migration ----
result = api_call('startTurboMigration', workerThreads=4, batchSize=500)
assert result['success'], f"Failed to start migration: {result['message']}"
migration_id = result['backgroundProcessId']
print(f"Migration started, process ID: {migration_id}")

# ---- Step 3: Monitor migration ----
for status in wait_for_completion(migration_id):
    # Parse the latest line from notes for display
    notes = status.get('notes', '')
    last_line = notes.strip().split('\n')[-1] if notes else ''
    elapsed = status['elapsedSeconds']
    print(f"[{elapsed}s] {last_line}")

print("Migration complete!")

# ---- Step 4: Verify migration landed ----
counts = api_call('getTurboCounts')
print(f"Aspen now has: {counts['aspen']['ilsRecords']} ILS records")

# ---- Step 5: Start Turbo Reindex ----
result = api_call('startTurboReindex', workerThreads=8)
assert result['success'], f"Failed to start reindex: {result['message']}"
reindex_id = result['backgroundProcessId']
print(f"Reindex started, process ID: {reindex_id}")

# ---- Step 6: Monitor reindex ----
for status in wait_for_completion(reindex_id):
    notes = status.get('notes', '')
    last_line = notes.strip().split('\n')[-1] if notes else ''
    elapsed = status['elapsedSeconds']
    print(f"[{elapsed}s] {last_line}")

print("Reindex complete!")

# ---- Step 7: Final verification ----
counts = api_call('getTurboCounts')
print(f"Final: {counts['aspen']['groupedWorks']} grouped works indexed")

# ---- Step 8: Re-enable background indexers ----
result = api_call('startAspenIndexers')
print(f"Indexers: {result['message']}")
```

---

## Status Reporting for Cerulean UI

The `notes` field in `getTurboStatus` provides rich, real-time data. Here's how
to parse it for display in Cerulean:

### Regex Patterns for Key Metrics

```python
import re

def parse_migration_notes(notes):
    """Extract structured metrics from migration notes."""
    metrics = {}

    # Extraction progress
    m = re.search(r'Extract: ([\d,]+) bibs \(([\d.]+)%\), ([\d,]+) items \| ([\d,]+) bibs/sec', notes)
    if m:
        metrics['extract_bibs'] = int(m.group(1).replace(',', ''))
        metrics['extract_pct'] = float(m.group(2))
        metrics['extract_items'] = int(m.group(3).replace(',', ''))
        metrics['extract_rate'] = int(m.group(4).replace(',', ''))

    # Load progress
    m = re.search(r'Load: ([\d,]+) records upserted \(([\d,]+) batches\) \| ([\d,]+) rec/sec', notes)
    if m:
        metrics['load_records'] = int(m.group(1).replace(',', ''))
        metrics['load_batches'] = int(m.group(2).replace(',', ''))
        metrics['load_rate'] = int(m.group(3).replace(',', ''))

    # Group progress
    m = re.search(r'Group: ([\d,]+) records grouped \| ([\d,]+) rec/sec', notes)
    if m:
        metrics['group_records'] = int(m.group(1).replace(',', ''))
        metrics['group_rate'] = int(m.group(2).replace(',', ''))

    # Final summary
    m = re.search(r'MIGRATION COMPLETE: ([\d,]+) bibs.*?(\d+) errors', notes)
    if m:
        metrics['total_bibs'] = int(m.group(1).replace(',', ''))
        metrics['total_errors'] = int(m.group(2))
        metrics['completed'] = True

    return metrics

def parse_reindex_notes(notes):
    """Extract structured metrics from reindex notes."""
    metrics = {}

    # Progress line
    m = re.search(r'Progress: ([\d,]+) / ([\d,]+) \(([\d.]+)%\) \| ([\d,]+) works/sec \| ETA: (.+?) \|', notes)
    if m:
        metrics['processed'] = int(m.group(1).replace(',', ''))
        metrics['total'] = int(m.group(2).replace(',', ''))
        metrics['pct'] = float(m.group(3))
        metrics['rate'] = int(m.group(4).replace(',', ''))
        metrics['eta'] = m.group(5)

    # Final summary
    m = re.search(r'COMPLETED: ([\d,]+) works in (.+?) \(([\d,]+) works/sec\) \| (\d+) errors', notes)
    if m:
        metrics['total_works'] = int(m.group(1).replace(',', ''))
        metrics['duration'] = m.group(2)
        metrics['rate'] = int(m.group(3).replace(',', ''))
        metrics['errors'] = int(m.group(4))
        metrics['completed'] = True

    return metrics
```

---

## Error Handling

| HTTP Status | Meaning | Action |
|-------------|---------|--------|
| 200 + `success: false` | Operation failed (e.g., already running) | Check `message` field |
| 200 + `success: true` | Operation succeeded | Use returned data |
| 403 | IP not whitelisted for API access | Configure IP in Aspen admin |
| 500 | Server error | Check Aspen logs |

---

## Recommended Poll Intervals

| Operation | Poll Interval | Typical Duration (470k bibs) |
|-----------|--------------|------------------------------|
| Turbo Migration | 10 seconds | 15-30 minutes |
| Turbo Reindex | 10 seconds | 15-30 minutes |
| Count verification | Once | Instant |
