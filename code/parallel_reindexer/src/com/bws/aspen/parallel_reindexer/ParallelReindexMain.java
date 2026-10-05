package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.config.ConfigUtil;
import com.turning_leaf_technologies.indexing.IndexingUtils;
import com.turning_leaf_technologies.logging.BaseIndexingLogEntry;
import com.turning_leaf_technologies.logging.LoggingUtil;
import com.turning_leaf_technologies.util.SystemUtils;
import org.apache.logging.log4j.Logger;
import org.aspen_discovery.reindexer.GroupedWorkIndexer;
import org.aspen_discovery.reindexer.NightlyIndexLogEntry;
import org.ini4j.Ini;

import java.io.File;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.concurrent.*;

/**
 * Drop-in replacement for GroupedReindexMain that processes grouped works
 * across multiple threads for dramatically faster full reindex times.
 *
 * Usage: java -jar parallel_reindexer.jar <serverName> [full|fullNoClear|nightly]
 *
 * This is a plugin — it does NOT modify any core Aspen Discovery code.
 * It instantiates multiple GroupedWorkIndexer instances (each with their own
 * DB connection) and coordinates them via a shared work queue.
 *
 * Architecture:
 *   1. Coordinator thread: fetches all work IDs, pushes into BlockingQueue
 *   2. N worker threads: each pulls from queue, processes via own GroupedWorkIndexer
 *   3. Shared ConcurrentUpdateHttp2SolrClient handles Solr writes (thread-safe)
 *   4. ProgressTracker provides real-time throughput stats
 *
 * @author BWS (ByWater Solutions)
 */
public class ParallelReindexMain {

	private static BaseIndexingLogEntry logEntry;
	private static Logger logger;
	private static String serverName;
	private static final String processName = "parallel_grouped_reindex";
	private static boolean fullReindex = false;
	private static boolean clearIndex = false;
	private static boolean isNightlyReindex = false;
	private static Ini configIni;
	private static String baseLogPath;
	private static Connection coordinatorDbConn = null;
	private static String jdbcUrl;

	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.println("Usage: parallel_reindexer <serverName> [full|fullNoClear|nightly]");
			System.out.println();
			System.out.println("  full        - Full reindex, clear existing index first");
			System.out.println("  fullNoClear - Full reindex, keep existing index");
			System.out.println("  nightly     - Nightly full reindex (checks triggers)");
			System.out.println();
			System.out.println("Configuration: place parallel_reindexer.properties next to the JAR");
			System.exit(1);
		}

		serverName = args[0];

		if (args.length >= 2) {
			switch (args[1].toLowerCase()) {
				case "full":
					fullReindex = true;
					clearIndex = true;
					break;
				case "fullnoclear":
				case "nightly":
					fullReindex = true;
					clearIndex = false;
					isNightlyReindex = args[1].equalsIgnoreCase("nightly");
					break;
				default:
					System.out.println("Unknown mode: " + args[1]);
					System.out.println("Valid modes: full, fullNoClear, nightly");
					System.exit(1);
			}
		}

		long overallStart = System.currentTimeMillis();

		initializeReindex();
		logEntry.addNote("Parallel Reindexer initialized");

		// Load configuration
		String configDir = configIni.get("Site", "baseLogPath");
		if (configDir == null) configDir = ".";
		ParallelReindexConfig config = ParallelReindexConfig.load(configDir);
		logEntry.addNote("Configuration: " + config);
		logger.info("Parallel reindexer configuration: " + config);

		try {
			boolean regroupAllRecords = false;
			if (fullReindex) {
				try {
					PreparedStatement stmt = coordinatorDbConn.prepareStatement("SELECT regroupAllRecordsDuringNightlyIndex FROM system_variables");
					ResultSet rs = stmt.executeQuery();
					if (rs.next()) {
						regroupAllRecords = rs.getBoolean("regroupAllRecordsDuringNightlyIndex");
					}
					stmt.close();
				} catch (Exception e) {
					logger.error("Unable to determine if we should regroup all records", e);
				}
			}

			// Phase 1: Count total works
			long totalWorksToIndex = countWorksToIndex();
			logEntry.addNote("Total works to index: " + totalWorksToIndex);
			logger.info("Total works to index: " + totalWorksToIndex);

			if (totalWorksToIndex == 0) {
				logEntry.addNote("No works to index, exiting");
				finishUp(overallStart);
				return;
			}

			// Phase 2: If clearIndex, do it once with a single indexer before workers start
			if (clearIndex) {
				logEntry.addNote("Clearing existing index...");
				logger.info("Clearing existing Solr index before parallel reindex");
				GroupedWorkIndexer clearIndexer = new GroupedWorkIndexer(serverName, coordinatorDbConn, configIni, fullReindex, true, regroupAllRecords, logEntry, logger);
				// The constructor clears the index when clearIndex=true
				clearIndexer.close();
				logEntry.addNote("Index cleared");
			}

			// Phase 3: Set up the work queue and progress tracker
			BlockingQueue<WorkUnit> workQueue = new LinkedBlockingQueue<>(config.getWorkQueueCapacity());
			ProgressTracker progressTracker = new ProgressTracker(totalWorksToIndex, config.getProgressReportInterval(), logger, logEntry);
			long indexStartTime = new Date().getTime() / 1000;

			// Phase 4: Start the work producer (reads DB, fills queue)
			Connection producerDbConn = createDbConnection();
			WorkProducer producer = new WorkProducer(producerDbConn, workQueue, config.getWorkerThreadCount(),
				fullReindex, getLastReindexTime(), logger);
			Thread producerThread = new Thread(producer, "work-producer");
			producerThread.start();

			// Phase 5: Start N worker threads
			logger.info("Starting " + config.getWorkerThreadCount() + " worker threads");
			logEntry.addNote("Starting " + config.getWorkerThreadCount() + " parallel worker threads");

			ExecutorService workerPool = Executors.newFixedThreadPool(config.getWorkerThreadCount(), r -> {
				Thread t = new Thread(r);
				t.setDaemon(false);
				return t;
			});

			IndexerWorker[] workers = new IndexerWorker[config.getWorkerThreadCount()];
			Future<?>[] futures = new Future<?>[config.getWorkerThreadCount()];

			for (int i = 0; i < config.getWorkerThreadCount(); i++) {
				workers[i] = new IndexerWorker(
					i, workQueue, progressTracker, serverName, configIni,
					fullReindex, false, regroupAllRecords, logEntry, logger,
					jdbcUrl, indexStartTime
				);
				futures[i] = workerPool.submit(workers[i]);
			}

			// Phase 6: Wait for all workers to complete
			workerPool.shutdown();
			try {
				// Wait up to 48 hours for a full reindex of 3M+ docs
				boolean finished = workerPool.awaitTermination(48, TimeUnit.HOURS);
				if (!finished) {
					logEntry.addNote("WARNING: Worker pool did not finish within 48 hours, forcing shutdown");
					logger.warn("Worker pool did not finish within 48 hours");
					workerPool.shutdownNow();
				}
			} catch (InterruptedException e) {
				logger.error("Main thread interrupted while waiting for workers", e);
				workerPool.shutdownNow();
				Thread.currentThread().interrupt();
			}

			// Wait for producer to finish too
			try {
				producerThread.join(60000); // 1 minute timeout
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}

			// Check for exceptions in workers
			for (int i = 0; i < futures.length; i++) {
				try {
					futures[i].get(); // will throw if the worker threw an uncaught exception
				} catch (ExecutionException e) {
					logger.error("Worker-" + i + " threw exception", e.getCause());
					logEntry.incErrors("Worker-" + i + " failed: " + e.getCause().getMessage());
				} catch (Exception e) {
					logger.error("Error checking worker-" + i + " result", e);
				}
			}

			// Phase 7: Final reporting and commit
			progressTracker.reportFinalStats();

			// Use one indexer for the final commit and cleanup
			// finishIndexing() is package-private, so we use reflection
			GroupedWorkIndexer finishIndexer = new GroupedWorkIndexer(serverName, coordinatorDbConn, configIni, fullReindex, false, regroupAllRecords, logEntry, logger);
			if (finishIndexer.isOkToIndex()) {
				try {
					java.lang.reflect.Method finishMethod = GroupedWorkIndexer.class.getDeclaredMethod("finishIndexing");
					finishMethod.setAccessible(true);
					finishMethod.invoke(finishIndexer);
				} catch (Exception e) {
					logger.error("Error calling finishIndexing via reflection", e);
				}
			}
			finishIndexer.close();

			// Clean up producer DB connection
			try {
				producerDbConn.close();
			} catch (SQLException e) {
				logger.warn("Error closing producer DB connection", e);
			}

		} catch (Error e) {
			logEntry.incErrors("Error processing parallel reindex: " + e);
		} catch (Exception e) {
			logEntry.incErrors("Exception processing parallel reindex: ", e);
		}

		finishUp(overallStart);
	}

	private static void finishUp(long overallStart) {
		long elapsed = System.currentTimeMillis() - overallStart;
		String duration = formatDuration(elapsed / 1000.0);
		logEntry.addNote("Parallel Reindex completed in " + duration + " for " + serverName);
		logEntry.setFinished();
		SystemUtils.printMemoryStats(logger);

		if (coordinatorDbConn != null) {
			try {
				coordinatorDbConn.close();
			} catch (SQLException e) {
				logger.error("Error closing coordinator DB connection", e);
			}
		}
		System.exit(0);
	}

	private static long countWorksToIndex() {
		try {
			PreparedStatement stmt;
			if (fullReindex) {
				stmt = coordinatorDbConn.prepareStatement(
					"SELECT COUNT(DISTINCT permanent_id) as cnt FROM grouped_work INNER JOIN grouped_work_records ON grouped_work.id = groupedWorkId"
				);
			} else {
				long lastReindexTime = getLastReindexTime();
				stmt = coordinatorDbConn.prepareStatement(
					"SELECT COUNT(id) as cnt FROM grouped_work WHERE date_updated IS NULL OR date_updated >= ?"
				);
				stmt.setLong(1, lastReindexTime);
			}
			ResultSet rs = stmt.executeQuery();
			rs.next();
			long count = rs.getLong("cnt");
			rs.close();
			stmt.close();
			return count;
		} catch (SQLException e) {
			logger.error("Error counting works to index", e);
			return 0;
		}
	}

	private static long getLastReindexTime() {
		try {
			PreparedStatement stmt = coordinatorDbConn.prepareStatement("SELECT value FROM variables WHERE name = 'last_reindex_time'");
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				return rs.getLong("value");
			}
			rs.close();
			stmt.close();
		} catch (SQLException e) {
			logger.error("Error loading last reindex time", e);
		}
		return 0;
	}

	private static Connection createDbConnection() throws SQLException {
		Connection conn = DriverManager.getConnection(jdbcUrl);
		conn.prepareCall("SET collation_connection = utf8mb4_general_ci").execute();
		conn.prepareCall("SET NAMES utf8mb4").execute();
		return conn;
	}

	private static void initializeReindex() {
		// Log rotation — same as core reindexer
		logger = LoggingUtil.setupLogging(serverName, processName);
		logger.info("Starting Parallel Reindex for " + serverName);

		configIni = ConfigUtil.loadConfigFile("config.ini", serverName, logger);
		baseLogPath = configIni.get("Site", "baseLogPath");

		// Clean up old log files
		File logFile = new File(baseLogPath + "/" + serverName + "/logs/parallel_grouped_reindex.log");
		if (logFile.exists()) logFile.delete();
		for (int i = 1; i <= 10; i++) {
			File rotated = new File(baseLogPath + "/" + serverName + "/logs/parallel_grouped_reindex.log." + i);
			if (rotated.exists()) rotated.delete();
		}

		logger.info("Setting up database connections");
		jdbcUrl = ConfigUtil.cleanIniValue(configIni.get("Database", "database_aspen_jdbc"));
		if (jdbcUrl == null || jdbcUrl.isEmpty()) {
			logger.error("Database connection information not found in Database Section (database_aspen_jdbc)");
			System.exit(1);
		}

		try {
			coordinatorDbConn = DriverManager.getConnection(jdbcUrl);
			coordinatorDbConn.prepareCall("SET collation_connection = utf8mb4_general_ci").execute();
			coordinatorDbConn.prepareCall("SET NAMES utf8mb4").execute();
			logger.info("Connected to Aspen database");
		} catch (SQLException e) {
			logger.error("Could not connect to Aspen database", e);
			System.exit(1);
		}

		logEntry = new NightlyIndexLogEntry(coordinatorDbConn, logger);

		// Handle nightly trigger checks (same as core reindexer)
		if (isNightlyReindex) {
			try {
				PreparedStatement stmt = coordinatorDbConn.prepareStatement("SELECT runNightlyFullIndex, nightlyIndexTrigger FROM system_variables");
				ResultSet rs = stmt.executeQuery();
				if (rs.next()) {
					boolean runNightlyFullIndex = rs.getBoolean("runNightlyFullIndex");
					String nightlyIndexTrigger = rs.getString("nightlyIndexTrigger");
					if (!runNightlyFullIndex) {
						logEntry.addNote("Nightly index does not need to be run");
						logEntry.setFinished();
						System.exit(0);
					}
					StringBuilder triggerNote = new StringBuilder("Parallel nightly reindex triggered by:");
					boolean hasTriggers = false;
					if (nightlyIndexTrigger != null && !nightlyIndexTrigger.isEmpty()) {
						for (String trigger : nightlyIndexTrigger.split("\n")) {
							String t = trigger.trim();
							if (!t.isEmpty()) {
								triggerNote.append(hasTriggers ? ", " : " ").append(t);
								hasTriggers = true;
							}
						}
					}
					if (System.console() != null) {
						String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
						triggerNote.append(hasTriggers ? ", " : " ").append("Manual CLI (Triggered at ").append(timestamp).append(")");
					}
					logEntry.addNote(triggerNote.toString());
					logEntry.saveResults();
				}
				stmt.close();

				// Reset the nightly flag
				coordinatorDbConn.prepareStatement("UPDATE system_variables SET runNightlyFullIndex = 0, nightlyIndexTrigger = NULL WHERE true").executeUpdate();
			} catch (SQLException e) {
				logger.error("Unable to check nightly index triggers", e);
			}
		}

		SystemUtils.printMemoryStats(logger);
	}

	private static String formatDuration(double seconds) {
		long h = (long) (seconds / 3600);
		long m = (long) ((seconds % 3600) / 60);
		long s = (long) (seconds % 60);
		if (h > 0) return String.format("%dh %dm %ds", h, m, s);
		if (m > 0) return String.format("%dm %ds", m, s);
		return String.format("%ds", s);
	}
}
