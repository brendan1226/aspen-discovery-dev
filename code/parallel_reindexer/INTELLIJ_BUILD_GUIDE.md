# Building Aspen Discovery Java Components with IntelliJ IDEA

## Part 1: Community Setup (Stock Aspen)

### Prerequisites

- **IntelliJ IDEA** — Community Edition (free) or Ultimate
- **JDK 17** — Download from [Adoptium](https://adoptium.net/) (Temurin 17 LTS)
- **Git** — To clone the Aspen Discovery repository
- **Solr 8.11.2** — Already included in the repo at `sites/default/solr-8.11.2/`

### Step 1: Clone the Repository

```bash
git clone https://github.com/Aspen-Discovery/aspen-discovery.git
cd aspen-discovery
```

### Step 2: Set Up the IntelliJ Project

Aspen ships a ready-made IntelliJ project template. Copy it into place:

```bash
cp -r default_intellij_project intellij_project
```

### Step 3: Open in IntelliJ

1. Open IntelliJ IDEA
2. **File → Open** → navigate to `aspen-discovery/intellij_project/`
3. Click **Open as Project**
4. IntelliJ will index the project (may take 1-2 minutes)

### Step 4: Configure the JDK

1. **File → Project Structure → Project**
2. Set **Project SDK** to Java 17 (add it if not listed: click the dropdown → Add SDK → Download)
3. Set **Project language level** to `11 - Local-variable syntax for lambda parameters`
4. Click **OK**

### Step 5: Build All Artifacts

1. **Build → Build Artifacts → All Artifacts → Build**
2. IntelliJ compiles all 28 modules and produces 22 JAR artifacts

The JARs are output to each module's directory:
- `code/reindexer/reindexer.jar`
- `code/koha_export/koha_export.jar`
- `code/evergreen_export/evergreen_export.jar`
- etc.

### Step 6: Deploy

Copy the built JAR(s) to your Aspen server:

```bash
# Example: deploy reindexer.jar
scp code/reindexer/reindexer.jar your-server:/usr/local/aspen-discovery/code/reindexer/

# Or for Docker:
docker cp code/reindexer/reindexer.jar containeraspen:/usr/local/aspen-discovery/code/reindexer/
```

### Project Structure

```
aspen-discovery/
├── intellij_project/          ← IntelliJ opens this directory
│   └── .idea/
│       ├── modules.xml        ← Lists all 28 modules
│       ├── misc.xml           ← JDK 17, language level 11
│       ├── artifacts/         ← 22 JAR build configurations
│       ├── libraries/         ← Shared dependency definitions
│       └── workspace.xml      ← Run configurations
│
├── code/
│   ├── java_shared_libraries/ ← Shared classes + third-party JARs
│   │   ├── src/               ← Source (includes marc4j, util classes)
│   │   ├── *.jar              ← Third-party JARs (log4j, mysql, etc.)
│   │   └── java_shared_libraries.iml
│   │
│   ├── reindexer/             ← Grouped work reindexer
│   │   ├── src/               ← Java source
│   │   ├── META-INF/MANIFEST.MF ← Classpath manifest
│   │   ├── reindexer.jar      ← Build output
│   │   └── reindexer.iml
│   │
│   ├── koha_export/           ← Koha ILS connector
│   ├── evergreen_export/      ← Evergreen ILS connector
│   ├── sierra_export_api/     ← Sierra ILS connector
│   └── ...                    ← 15+ other modules
│
└── sites/default/solr-8.11.2/ ← Solr (compile dependency)
```

### How the Build Works

Each JAR artifact is defined in `.idea/artifacts/<name>.xml`. For example, `reindexer.xml`:

```xml
<artifact type="jar" build-on-make="true" name="reindexer">
  <output-path>$PROJECT_DIR$/../code/reindexer</output-path>
  <root id="archive" name="reindexer.jar">
    <element id="module-output" name="reindexer" />           ← Reindexer classes
    <element id="directory" name="META-INF">
      <element id="file-copy" path=".../META-INF/MANIFEST.MF" /> ← Classpath manifest
    </element>
    <element id="module-output" name="java_shared_libraries" /> ← Shared lib classes
  </root>
</artifact>
```

This produces a thin JAR containing:
- Compiled reindexer classes (`org/aspen_discovery/reindexer/`)
- Compiled shared library classes (`com/turning_leaf_technologies/`, `org/marc4j/`)
- `META-INF/MANIFEST.MF` with `Class-Path` pointing to third-party JARs

The third-party JARs (log4j, mysql-connector, jsch, etc.) are NOT bundled inside
the JAR — they're resolved at runtime via the `Class-Path` manifest entry pointing
to `../java_shared_libraries/*.jar` and `../../sites/default/solr-8.11.2/dist/`.

### Run Configurations

IntelliJ includes pre-configured run configurations for every module. To run the
reindexer locally:

1. Open the **Run** dropdown (top right)
2. Select **Reindexer Full** (or Reindexer Single Work, etc.)
3. Edit the configuration to set the program argument to your server name
4. Click **Run**

---

## Part 2: Building the BWS Threaded Reindexer

### What We Changed

Two files in the `reindexer` module:

| File | Change |
|------|--------|
| `GroupedWorkIndexer.java` | Configurable `solrThreadCount`, `solrQueueSize`, `numReindexWorkerThreads` from `system_variables` table |
| `GroupedReindexMain.java` | New `processGroupedWorksThreaded()` method — producer/consumer with N parallel workers |

### Branch

```bash
git checkout feature/threaded-reindexer
```

### Build in IntelliJ

1. Open the project as described in Part 1
2. Switch to the `feature/threaded-reindexer` branch (VCS → Git → Branches)
3. **Build → Build Artifacts → reindexer → Build**
4. Output: `code/reindexer/reindexer.jar`

### Database Migration (run once on target Aspen)

```sql
ALTER TABLE system_variables
  ADD COLUMN IF NOT EXISTS solrThreadCount int(11) DEFAULT 1,
  ADD COLUMN IF NOT EXISTS solrQueueSize int(11) DEFAULT 25,
  ADD COLUMN IF NOT EXISTS numReindexWorkerThreads int(11) DEFAULT 1;

-- Recommended production values:
UPDATE system_variables SET solrThreadCount = 4, solrQueueSize = 200, numReindexWorkerThreads = 4;
```

### Deploy

```bash
# Backup stock jar
docker exec containeraspen cp /usr/local/aspen-discovery/code/reindexer/reindexer.jar \
    /usr/local/aspen-discovery/code/reindexer/reindexer.jar.stock

# Deploy threaded version
docker cp code/reindexer/reindexer.jar \
    containeraspen:/usr/local/aspen-discovery/code/reindexer/reindexer.jar
```

### Rollback

```bash
# Option A: Restore stock JAR
docker exec containeraspen cp /usr/local/aspen-discovery/code/reindexer/reindexer.jar.stock \
    /usr/local/aspen-discovery/code/reindexer/reindexer.jar

# Option B: Just disable threading (no JAR swap)
# In Aspen admin → System Variables, or:
mysql> UPDATE system_variables SET numReindexWorkerThreads = 1;
```

### Tuning

| Setting | Conservative | Recommended | Aggressive |
|---------|-------------|-------------|------------|
| `numReindexWorkerThreads` | 2 | 4 | 8 |
| `solrThreadCount` | 2 | 4 | 8 |
| `solrQueueSize` | 50 | 200 | 500 |

Memory: each worker uses ~300-500MB. Ensure JVM heap can handle it:
- 4 workers: `-Xmx2g`
- 8 workers: `-Xmx4g`

---

## Part 3: Sharing Changes with the Community

### Option A: Pull Request (recommended)

1. Fork `Aspen-Discovery/aspen-discovery` on GitHub
2. Push the `feature/threaded-reindexer` branch to your fork
3. Open a PR against the `26.05.00` branch (or current dev branch)
4. Include benchmark data (the THREADED_REINDEXER.md results)

### Option B: Patch File

```bash
# Generate a patch from the threaded reindexer branch
git diff origin/26.05.00..feature/threaded-reindexer -- \
    code/reindexer/src/ install/aspen.sql \
    > threaded-reindexer.patch

# Someone else applies it:
git apply threaded-reindexer.patch
```

### Option C: Distribute Just the JAR

The built `reindexer.jar` can be dropped into any Aspen 26.03+ installation.
Distribute with:
- The JAR file
- The SQL migration (3 ALTER TABLE lines)
- The `THREADED_REINDEXER.md` doc

The JAR is backwards-compatible — if the `system_variables` columns don't exist,
it falls back to defaults (1 thread, queue 25) which is identical to stock behavior.

---

## Quick Reference: All Aspen Java Modules

| Module | Main Class | Purpose |
|--------|-----------|---------|
| reindexer | `GroupedReindexMain` | Nightly/full reindex of grouped works → Solr |
| koha_export | `KohaExportMain` | Extract records from Koha → Aspen DB |
| evergreen_export | `EvergreenExportMain` | Extract records from Evergreen → Aspen DB |
| sierra_export_api | `SierraExportAPIMain` | Extract records from Sierra → Aspen DB |
| symphony_export | `SymphonyExportMain` | Extract records from Symphony → Aspen DB |
| polaris_export | `PolarisExportMain` | Extract records from Polaris → Aspen DB |
| carlx_export | `CarlXExportMain` | Extract records from CarlX → Aspen DB |
| folio_export | `FolioExportMain` | Extract records from FOLIO → Aspen DB |
| evolve_export | `EvolveExportMain` | Extract records from Evolve → Aspen DB |
| overdrive_extract | `ExtractOverDriveInfoMain` | OverDrive eContent sync |
| cloud_library_export | `CloudLibraryExportMain` | CloudLibrary eContent sync |
| hoopla_export | `HooplaExportMain` | Hoopla eContent sync |
| palace_project_export | `PalaceProjectExportMain` | Palace Project eContent sync |
| axis_360_export | `Axis360ExportMain` | Axis 360 eContent sync |
| sideload_processing | `SideLoadingMain` | Side-loaded MARC processing |
| oai_indexer | OAI harvester | OAI-PMH record harvesting |
| events_indexer | Events indexer | Community events indexing |
| series_indexer | Series indexer | Series data indexing |
| course_reserves_indexer | Course reserves | Academic course reserves |
| user_list_indexer | User lists | Public user list indexing |
| web_indexer | Web pages | Website content indexing |
| cron | `Cron` | Scheduled maintenance tasks |
