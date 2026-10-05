package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.config.ConfigUtil;
import com.turning_leaf_technologies.logging.BaseIndexingLogEntry;
import com.turning_leaf_technologies.logging.LoggingUtil;
import com.turning_leaf_technologies.util.SystemUtils;
import org.apache.logging.log4j.Logger;
import org.aspen_discovery.reindexer.NightlyIndexLogEntry;
import org.ini4j.Ini;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.concurrent.*;

/**
 * Entry point for Turbo Migration: bulk extract from Koha → batch load + parallel
 * group into Aspen's database.
 *
 * This handles Phases 1-2. Phase 3 (Solr indexing) is triggered separately via
 * the Turbo Reindex.
 *
 * Usage:
 *   java -cp ... com.bws.aspen.parallel_reindexer.TurboMigrationMain
 *        <serverName> [workerThreads] [batchSize] [profileName]
 *
 * Arguments:
 *   serverName     - Aspen server name (required)
 *   workerThreads  - Number of parallel load/group workers (default: 4)
 *   batchSize      - Records per batch (default: 500)
 *   profileName    - Indexing profile name (default: "ils")
 *
 * @author BWS (ByWater Solutions)
 */
public class TurboMigrationMain {

	private static Logger logger;
	private static BaseIndexingLogEntry logEntry;
	private static String serverName;
	private static Ini configIni;
	private static Connection aspenDbConn;
	private static String aspenJdbcUrl;

	public static void main(String[] args) {
		if (args.length == 0) {
			System.out.println("Usage: TurboMigrationMain <serverName> [workerThreads] [batchSize] [profileName]");
			System.exit(1);
		}

		serverName = args[0];
		int workerThreads = args.length > 1 ? Integer.parseInt(args[1]) : 4;
		int batchSize = args.length > 2 ? Integer.parseInt(args[2]) : 500;
		String profileName = args.length > 3 ? args[3] : "ils";

		long overallStart = System.currentTimeMillis();

		initialize();
		logEntry.addNote("Turbo Migration starting: " + workerThreads + " workers, batch=" + batchSize + ", profile=" + profileName);
		logger.info("Turbo Migration: serverName=" + serverName + ", workers=" + workerThreads + ", batch=" + batchSize + ", profile=" + profileName);

		try {
			// Load Koha connection info from account_profiles
			String kohaJdbcUrl = loadKohaConnectionUrl();
			if (kohaJdbcUrl == null) {
				logEntry.addNote("ERROR: Could not load Koha database connection from account_profiles");
				finishUp(overallStart, false);
				return;
			}
			logger.info("Koha JDBC URL loaded from account_profiles");

			// Count bibs in Koha for progress tracking
			long totalKohaBibs = countKohaBibs(kohaJdbcUrl);
			logEntry.addNote("Koha bib count: " + totalKohaBibs);
			logger.info("Total bibs in Koha: " + totalKohaBibs);

			if (totalKohaBibs == 0) {
				logEntry.addNote("No bibs found in Koha, nothing to migrate");
				finishUp(overallStart, true);
				return;
			}

			// Set up the pipeline
			int queueCapacity = Math.max(10, workerThreads * 5); // batches in queue
			BlockingQueue<MarcBatch> batchQueue = new LinkedBlockingQueue<>(queueCapacity);
			MigrationProgressTracker tracker = new MigrationProgressTracker(totalKohaBibs, 10000, logger, logEntry);

			// Phase 1: Start the extractor thread (reads from Koha)
			Connection kohaConn = DriverManager.getConnection(kohaJdbcUrl);
			KohaBulkExtractor extractor = new KohaBulkExtractor(kohaConn, batchQueue, workerThreads, batchSize, tracker, logger);
			Thread extractorThread = new Thread(extractor, "koha-bulk-extractor");
			extractorThread.start();

			// Phase 2: Start N worker threads (load + group into Aspen)
			ExecutorService workerPool = Executors.newFixedThreadPool(workerThreads);
			Future<?>[] futures = new Future<?>[workerThreads];

			for (int i = 0; i < workerThreads; i++) {
				BulkLoadWorker worker = new BulkLoadWorker(
					i, batchQueue, tracker, serverName, aspenJdbcUrl, profileName, logEntry, logger
				);
				futures[i] = workerPool.submit(worker);
			}

			// Wait for all workers
			workerPool.shutdown();
			boolean finished = workerPool.awaitTermination(24, TimeUnit.HOURS);
			if (!finished) {
				logger.warn("Worker pool did not finish within 24 hours, forcing shutdown");
				workerPool.shutdownNow();
			}

			// Wait for extractor
			extractorThread.join(60000);

			// Check for worker exceptions
			for (int i = 0; i < futures.length; i++) {
				try {
					futures[i].get();
				} catch (ExecutionException e) {
					logger.error("Worker-" + i + " threw exception", e.getCause());
					logEntry.incErrors("Worker-" + i + " failed: " + e.getCause().getMessage());
				}
			}

			// Close Koha connection
			try { kohaConn.close(); } catch (Exception e) { /* ignore */ }

			// Final report
			tracker.reportFinal();
			logEntry.addNote("Turbo Migration Phase 1-2 complete. Run Turbo Reindex to index into Solr.");

			finishUp(overallStart, true);

		} catch (Exception e) {
			logger.error("Turbo Migration failed", e);
			logEntry.incErrors("Turbo Migration failed: " + e.getMessage());
			finishUp(overallStart, false);
		}
	}

	private static String loadKohaConnectionUrl() {
		try {
			PreparedStatement stmt = aspenDbConn.prepareStatement(
				"SELECT databaseHost, databasePort, databaseName, databaseUser, databasePassword, databaseTimezone " +
				"FROM account_profiles WHERE ils = 'koha' LIMIT 1"
			);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				String host = rs.getString("databaseHost");
				if (host == null || host.isEmpty()) host = "localhost";
				String port = rs.getString("databasePort");
				if (port == null || port.isEmpty()) port = "3306";
				String dbName = rs.getString("databaseName");
				String user = rs.getString("databaseUser");
				String password = rs.getString("databasePassword");
				String timezone = rs.getString("databaseTimezone");

				String url = "jdbc:mysql://" + host + ":" + port + "/" + dbName +
					"?user=" + URLEncoder.encode(user, StandardCharsets.UTF_8) +
					"&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8) +
					"&useUnicode=yes&characterEncoding=UTF-8";
				if (timezone != null && !timezone.isEmpty()) {
					url += "&serverTimezone=" + URLEncoder.encode(timezone, StandardCharsets.UTF_8);
				}
				rs.close();
				stmt.close();
				return url;
			}
			rs.close();
			stmt.close();
		} catch (Exception e) {
			logger.error("Error loading Koha connection from account_profiles", e);
		}
		return null;
	}

	private static long countKohaBibs(String kohaJdbcUrl) {
		try (Connection conn = DriverManager.getConnection(kohaJdbcUrl)) {
			PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM biblio_metadata");
			ResultSet rs = stmt.executeQuery();
			rs.next();
			long count = rs.getLong(1);
			rs.close();
			stmt.close();
			return count;
		} catch (Exception e) {
			logger.error("Error counting Koha bibs", e);
			return 0;
		}
	}

	private static void initialize() {
		logger = LoggingUtil.setupLogging(serverName, "turbo_migration");
		logger.info("Initializing Turbo Migration for " + serverName);

		configIni = ConfigUtil.loadConfigFile("config.ini", serverName, logger);

		aspenJdbcUrl = ConfigUtil.cleanIniValue(configIni.get("Database", "database_aspen_jdbc"));
		if (aspenJdbcUrl == null || aspenJdbcUrl.isEmpty()) {
			logger.error("database_aspen_jdbc not found in config.ini");
			System.exit(1);
		}

		try {
			aspenDbConn = DriverManager.getConnection(aspenJdbcUrl);
			aspenDbConn.prepareCall("SET collation_connection = utf8mb4_general_ci").execute();
			aspenDbConn.prepareCall("SET NAMES utf8mb4").execute();
		} catch (SQLException e) {
			logger.error("Could not connect to Aspen database", e);
			System.exit(1);
		}

		logEntry = new NightlyIndexLogEntry(aspenDbConn, logger);
		SystemUtils.printMemoryStats(logger);
	}

	private static void finishUp(long overallStart, boolean success) {
		long elapsed = System.currentTimeMillis() - overallStart;
		double seconds = elapsed / 1000.0;
		long m = (long) (seconds / 60);
		long s = (long) (seconds % 60);
		String duration = m > 0 ? m + "m " + s + "s" : s + "s";

		logEntry.addNote("Turbo Migration finished in " + duration + (success ? " (success)" : " (with errors)"));
		logEntry.setFinished();
		SystemUtils.printMemoryStats(logger);

		try {
			if (aspenDbConn != null) aspenDbConn.close();
		} catch (Exception e) {
			logger.error("Error closing Aspen DB connection", e);
		}

		System.exit(success ? 0 : 1);
	}
}
