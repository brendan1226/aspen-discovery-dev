package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.logging.BaseIndexingLogEntry;
import org.apache.logging.log4j.Logger;
import org.aspen_discovery.reindexer.GroupedWorkIndexer;
import org.ini4j.Ini;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;

/**
 * A worker thread that pulls WorkUnits from the shared queue and processes
 * them using its own dedicated GroupedWorkIndexer instance (with its own DB connection).
 *
 * Each worker is fully independent — no shared mutable state except the queue and the
 * thread-safe progress tracker.
 */
public class IndexerWorker implements Runnable {

	private final int workerId;
	private final BlockingQueue<WorkUnit> workQueue;
	private final ProgressTracker progressTracker;
	private final String serverName;
	private final Ini configIni;
	private final boolean fullReindex;
	private final boolean clearIndex;
	private final boolean regroupAllRecords;
	private final BaseIndexingLogEntry logEntry;
	private final Logger logger;
	private final String jdbcUrl;
	private final long indexStartTime;

	private Connection dbConn;
	private GroupedWorkIndexer indexer;
	private Method processGroupedWorkMethod;
	private long worksProcessed = 0;

	public IndexerWorker(int workerId, BlockingQueue<WorkUnit> workQueue, ProgressTracker progressTracker,
						 String serverName, Ini configIni, boolean fullReindex, boolean clearIndex,
						 boolean regroupAllRecords, BaseIndexingLogEntry logEntry, Logger logger,
						 String jdbcUrl, long indexStartTime) {
		this.workerId = workerId;
		this.workQueue = workQueue;
		this.progressTracker = progressTracker;
		this.serverName = serverName;
		this.configIni = configIni;
		this.fullReindex = fullReindex;
		// Only worker 0 should clear the index (and the main thread handles this before workers start)
		this.clearIndex = false;
		this.regroupAllRecords = regroupAllRecords;
		this.logEntry = logEntry;
		this.logger = logger;
		this.jdbcUrl = jdbcUrl;
		this.indexStartTime = indexStartTime;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("indexer-worker-" + workerId);
		logger.info("Worker-" + workerId + " starting");

		try {
			initializeWorker();
			if (indexer == null || !indexer.isOkToIndex() || processGroupedWorkMethod == null) {
				logger.error("Worker-" + workerId + " failed to initialize indexer, shutting down");
				return;
			}

			processWorkLoop();
		} catch (Exception e) {
			logger.error("Worker-" + workerId + " encountered fatal error", e);
		} finally {
			cleanup();
			logger.info("Worker-" + workerId + " finished, processed " + worksProcessed + " works");
		}
	}

	private void initializeWorker() {
		try {
			// Each worker gets its own DB connection
			dbConn = DriverManager.getConnection(jdbcUrl);
			dbConn.prepareCall("SET collation_connection = utf8mb4_general_ci").execute();
			dbConn.prepareCall("SET NAMES utf8mb4").execute();
			logger.info("Worker-" + workerId + " connected to database");

			// Each worker gets its own GroupedWorkIndexer with its own PreparedStatements
			// clearIndex=false for all workers — clearing is done once by the coordinator
			indexer = new GroupedWorkIndexer(serverName, dbConn, configIni, fullReindex, false, regroupAllRecords, logEntry, logger);

			// processGroupedWork is package-private, so we use reflection to access it
			// without modifying core Aspen code
			processGroupedWorkMethod = GroupedWorkIndexer.class.getDeclaredMethod(
				"processGroupedWork", Long.class, String.class, String.class);
			processGroupedWorkMethod.setAccessible(true);

			logger.info("Worker-" + workerId + " initialized GroupedWorkIndexer");
		} catch (SQLException e) {
			logger.error("Worker-" + workerId + " failed to initialize", e);
		} catch (NoSuchMethodException e) {
			logger.error("Worker-" + workerId + " could not find processGroupedWork method via reflection", e);
		}
	}

	private void processWorkLoop() {
		PreparedStatement setLastUpdatedTime = null;
		try {
			setLastUpdatedTime = dbConn.prepareStatement("UPDATE grouped_work SET date_updated = ? WHERE id = ?");
		} catch (SQLException e) {
			logger.error("Worker-" + workerId + " failed to prepare setLastUpdatedTime statement", e);
			return;
		}

		while (true) {
			WorkUnit work;
			try {
				work = workQueue.take(); // blocks until work is available
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				logger.info("Worker-" + workerId + " interrupted, shutting down");
				return;
			}

			if (work.isPoisonPill()) {
				logger.info("Worker-" + workerId + " received poison pill, shutting down");
				return;
			}

			try {
				processGroupedWorkMethod.invoke(indexer, work.getId(), work.getPermanentId(), work.getGroupingCategory());
				worksProcessed++;
				progressTracker.recordSuccess(workerId);

				// If dateUpdated was null, set it to just before index start
				if (work.getDateUpdated() == null) {
					setLastUpdatedTime.setLong(1, indexStartTime - 1);
					setLastUpdatedTime.setLong(2, work.getId());
					setLastUpdatedTime.executeUpdate();
				}
			} catch (Exception e) {
				progressTracker.recordError(workerId, work.getPermanentId(), e);
			}
		}
	}

	private void cleanup() {
		if (indexer != null) {
			try {
				indexer.close();
			} catch (Exception e) {
				logger.error("Worker-" + workerId + " error closing indexer", e);
			}
		}
		if (dbConn != null) {
			try {
				dbConn.close();
			} catch (SQLException e) {
				logger.error("Worker-" + workerId + " error closing DB connection", e);
			}
		}
	}

	public long getWorksProcessed() { return worksProcessed; }
}
