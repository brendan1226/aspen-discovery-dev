package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.indexing.IndexingProfile;
import com.turning_leaf_technologies.logging.BaseIndexingLogEntry;
import org.apache.logging.log4j.Logger;
import org.aspen_discovery.grouping.MarcRecordGrouper;

import java.sql.*;
import java.util.Date;
import java.util.concurrent.BlockingQueue;

/**
 * Phase 2 worker: pulls MarcBatch objects from the queue, batch-upserts them
 * into ils_records, then groups each record into grouped_work tables.
 *
 * Each worker has its own DB connection and its own MarcRecordGrouper instance
 * for thread safety. The ON DUPLICATE KEY UPDATE SQL handles both fresh migration
 * (INSERT) and re-sync (UPDATE) cases without per-record existence checks.
 */
public class BulkLoadWorker implements Runnable {

	private final int workerId;
	private final BlockingQueue<MarcBatch> batchQueue;
	private final MigrationProgressTracker tracker;
	private final String serverName;
	private final String jdbcUrl;
	private final String profileName;
	private final BaseIndexingLogEntry logEntry;
	private final Logger logger;

	private Connection dbConn;
	private MarcRecordGrouper recordGrouper;
	private PreparedStatement upsertStmt;

	public BulkLoadWorker(int workerId, BlockingQueue<MarcBatch> batchQueue,
						  MigrationProgressTracker tracker, String serverName,
						  String jdbcUrl, String profileName,
						  BaseIndexingLogEntry logEntry, Logger logger) {
		this.workerId = workerId;
		this.batchQueue = batchQueue;
		this.tracker = tracker;
		this.serverName = serverName;
		this.jdbcUrl = jdbcUrl;
		this.profileName = profileName;
		this.logEntry = logEntry;
		this.logger = logger;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("bulk-load-worker-" + workerId);
		logger.info("BulkLoadWorker-" + workerId + " starting");

		try {
			initialize();
			processLoop();
		} catch (Exception e) {
			logger.error("BulkLoadWorker-" + workerId + " fatal error", e);
			tracker.recordError("worker-" + workerId, e);
		} finally {
			cleanup();
			logger.info("BulkLoadWorker-" + workerId + " finished");
		}
	}

	private void initialize() throws SQLException {
		// Own DB connection
		dbConn = DriverManager.getConnection(jdbcUrl);
		dbConn.prepareCall("SET collation_connection = utf8mb4_general_ci").execute();
		dbConn.prepareCall("SET NAMES utf8mb4").execute();
		dbConn.setAutoCommit(false); // We'll commit per batch

		// Batch upsert statement for ils_records
		upsertStmt = dbConn.prepareStatement(
			"INSERT INTO ils_records (ilsId, source, checksum, dateFirstDetected, deleted, " +
			"suppressedNoMarcAvailable, sourceData, lastModified) " +
			"VALUES (?, ?, ?, ?, 0, 0, COMPRESS(?), ?) " +
			"ON DUPLICATE KEY UPDATE " +
			"checksum = VALUES(checksum), sourceData = VALUES(sourceData), " +
			"lastModified = VALUES(lastModified), deleted = 0, suppressedNoMarcAvailable = 0"
		);

		// Own MarcRecordGrouper — needs an IndexingProfile loaded from DB
		IndexingProfile indexingProfile = loadIndexingProfile();
		if (indexingProfile != null) {
			recordGrouper = new MarcRecordGrouper(serverName, dbConn, indexingProfile, logEntry, logger);
			logger.info("BulkLoadWorker-" + workerId + " initialized with profile '" + profileName + "'");
		} else {
			logger.error("BulkLoadWorker-" + workerId + " could not load indexing profile '" + profileName + "'");
		}
	}

	private IndexingProfile loadIndexingProfile() {
		try {
			PreparedStatement stmt = dbConn.prepareStatement("SELECT * FROM indexing_profiles WHERE name = ?");
			stmt.setString(1, profileName);
			ResultSet rs = stmt.executeQuery();
			if (rs.next()) {
				IndexingProfile profile = new IndexingProfile(serverName, rs, dbConn, logEntry);
				rs.close();
				stmt.close();
				return profile;
			}
			rs.close();
			stmt.close();
		} catch (Exception e) {
			logger.error("Error loading indexing profile", e);
		}
		return null;
	}

	private void processLoop() {
		while (true) {
			MarcBatch batch;
			try {
				batch = batchQueue.take();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}

			if (batch.isPoisonPill()) {
				logger.info("BulkLoadWorker-" + workerId + " received poison pill");
				return;
			}

			try {
				processBatch(batch);
			} catch (Exception e) {
				logger.error("BulkLoadWorker-" + workerId + " error processing batch", e);
				tracker.recordError("worker-" + workerId + "-batch", e);
			}
		}
	}

	private void processBatch(MarcBatch batch) throws SQLException {
		long now = new Date().getTime() / 1000;

		// Stage 2a: Batch upsert into ils_records
		for (MarcBatch.Entry entry : batch.getEntries()) {
			upsertStmt.setString(1, entry.getIlsId());
			upsertStmt.setString(2, profileName);
			upsertStmt.setLong(3, entry.getChecksum());
			upsertStmt.setLong(4, now);
			upsertStmt.setBytes(5, entry.getMarcJsonBytes());
			upsertStmt.setLong(6, now);
			upsertStmt.addBatch();
		}

		upsertStmt.executeBatch();
		dbConn.commit();
		tracker.recordBatchLoaded(batch.size());

		// Stage 2b: Group each record
		if (recordGrouper != null) {
			int grouped = 0;
			for (MarcBatch.Entry entry : batch.getEntries()) {
				try {
					// processMarcRecord returns the grouped work permanent_id (or null)
					recordGrouper.processMarcRecord(entry.getMarcRecord(), true, null, null);
					grouped++;
				} catch (Exception e) {
					tracker.recordError("group-" + entry.getIlsId(), e);
				}
			}
			dbConn.commit();
			tracker.recordBatchGrouped(grouped);
		}
	}

	private void cleanup() {
		try {
			if (upsertStmt != null) upsertStmt.close();
		} catch (SQLException e) {
			logger.warn("Error closing upsert statement", e);
		}
		try {
			if (dbConn != null) {
				dbConn.setAutoCommit(true);
				dbConn.close();
			}
		} catch (SQLException e) {
			logger.warn("Error closing DB connection", e);
		}
	}
}
