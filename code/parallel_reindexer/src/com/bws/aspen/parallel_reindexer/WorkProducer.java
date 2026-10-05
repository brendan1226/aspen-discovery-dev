package com.bws.aspen.parallel_reindexer;

import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;

/**
 * Runs on a dedicated thread to stream grouped work IDs from the database
 * into a shared BlockingQueue for worker consumption.
 *
 * This decouples the database cursor from the worker threads, allowing
 * the DB to stream results while workers process them in parallel.
 */
public class WorkProducer implements Runnable {

	private final Connection dbConn;
	private final BlockingQueue<WorkUnit> workQueue;
	private final int numWorkers;
	private final boolean fullReindex;
	private final long lastReindexTime;
	private final Logger logger;
	private long totalEnqueued = 0;

	public WorkProducer(Connection dbConn, BlockingQueue<WorkUnit> workQueue, int numWorkers,
						boolean fullReindex, long lastReindexTime, Logger logger) {
		this.dbConn = dbConn;
		this.workQueue = workQueue;
		this.numWorkers = numWorkers;
		this.fullReindex = fullReindex;
		this.lastReindexTime = lastReindexTime;
		this.logger = logger;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("work-producer");
		try {
			logger.info("WorkProducer starting, fullReindex=" + fullReindex);
			enqueueWorks();
			logger.info("WorkProducer finished enqueuing " + totalEnqueued + " works, sending poison pills");
		} catch (Exception e) {
			logger.error("WorkProducer encountered fatal error", e);
		} finally {
			// Send one poison pill per worker to signal shutdown
			for (int i = 0; i < numWorkers; i++) {
				try {
					workQueue.put(WorkUnit.POISON_PILL);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					logger.error("Interrupted sending poison pill", e);
				}
			}
		}
	}

	private void enqueueWorks() throws SQLException, InterruptedException {
		PreparedStatement stmt;
		if (fullReindex) {
			stmt = dbConn.prepareStatement(
				"SELECT grouped_work.id, permanent_id, grouping_category, date_updated " +
				"FROM grouped_work INNER JOIN grouped_work_records ON grouped_work.id = groupedWorkId " +
				"GROUP BY permanent_id",
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY
			);
		} else {
			stmt = dbConn.prepareStatement(
				"SELECT * FROM grouped_work WHERE date_updated IS NULL OR date_updated >= ?",
				ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY
			);
			stmt.setLong(1, lastReindexTime);
		}

		// Enable streaming result set for MySQL (avoids loading all rows into memory)
		stmt.setFetchSize(Integer.MIN_VALUE);

		ResultSet rs = stmt.executeQuery();
		while (rs.next()) {
			long id = rs.getLong("id");
			String permanentId = rs.getString("permanent_id");
			String groupingCategory = rs.getString("grouping_category");
			Long dateUpdated = rs.getLong("date_updated");
			if (rs.wasNull()) {
				dateUpdated = null;
			}

			WorkUnit work = new WorkUnit(id, permanentId, groupingCategory, dateUpdated);
			workQueue.put(work); // blocks if queue is full — backpressure
			totalEnqueued++;

			if (totalEnqueued % 100000 == 0) {
				logger.info("WorkProducer enqueued " + totalEnqueued + " works so far (queue size: " + workQueue.size() + ")");
			}
		}
		rs.close();
		stmt.close();
	}

	public long getTotalEnqueued() {
		return totalEnqueued;
	}
}
