package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.logging.BaseIndexingLogEntry;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe progress tracker shared across all worker threads.
 * Provides atomic counters and periodic logging.
 */
public class ProgressTracker {

	private final AtomicLong totalWorksProcessed = new AtomicLong(0);
	private final AtomicLong totalWorksErrored = new AtomicLong(0);
	private final AtomicLong totalSolrDocsAdded = new AtomicLong(0);
	private final ConcurrentHashMap<Integer, AtomicLong> perWorkerCounts = new ConcurrentHashMap<>();

	private final long totalWorksToIndex;
	private final int reportInterval;
	private final Logger logger;
	private final BaseIndexingLogEntry logEntry;
	private final long startTimeMs;

	public ProgressTracker(long totalWorksToIndex, int reportInterval, Logger logger, BaseIndexingLogEntry logEntry) {
		this.totalWorksToIndex = totalWorksToIndex;
		this.reportInterval = reportInterval;
		this.logger = logger;
		this.logEntry = logEntry;
		this.startTimeMs = System.currentTimeMillis();
	}

	/**
	 * Called by a worker after successfully processing one grouped work.
	 * Returns the new total count.
	 */
	public long recordSuccess(int workerId) {
		getWorkerCounter(workerId).incrementAndGet();
		long total = totalWorksProcessed.incrementAndGet();
		totalSolrDocsAdded.incrementAndGet();
		if (total % reportInterval == 0) {
			reportProgress(total);
		}
		return total;
	}

	/**
	 * Called by a worker when processing a grouped work fails.
	 */
	public void recordError(int workerId, String permanentId, Exception e) {
		totalWorksErrored.incrementAndGet();
		logger.error("Worker-" + workerId + " error processing " + permanentId, e);
	}

	private void reportProgress(long total) {
		long elapsedMs = System.currentTimeMillis() - startTimeMs;
		double elapsedSec = elapsedMs / 1000.0;
		double worksPerSec = total / elapsedSec;
		double pctDone = totalWorksToIndex > 0 ? (total * 100.0 / totalWorksToIndex) : 0;
		long remaining = totalWorksToIndex - total;
		double etaSec = worksPerSec > 0 ? remaining / worksPerSec : 0;

		String msg = String.format(
			"Progress: %,d / %,d (%.1f%%) | %.0f works/sec | ETA: %s | Errors: %d",
			total, totalWorksToIndex, pctDone, worksPerSec, formatDuration(etaSec), totalWorksErrored.get()
		);
		logger.info(msg);
		logEntry.addNote(msg);
	}

	private AtomicLong getWorkerCounter(int workerId) {
		return perWorkerCounts.computeIfAbsent(workerId, k -> new AtomicLong(0));
	}

	private String formatDuration(double seconds) {
		if (seconds <= 0) return "N/A";
		long h = (long) (seconds / 3600);
		long m = (long) ((seconds % 3600) / 60);
		long s = (long) (seconds % 60);
		if (h > 0) return String.format("%dh %dm %ds", h, m, s);
		if (m > 0) return String.format("%dm %ds", m, s);
		return String.format("%ds", s);
	}

	public void reportFinalStats() {
		long elapsedMs = System.currentTimeMillis() - startTimeMs;
		double elapsedSec = elapsedMs / 1000.0;
		long total = totalWorksProcessed.get();
		double worksPerSec = elapsedSec > 0 ? total / elapsedSec : 0;

		String msg = String.format(
			"COMPLETED: %,d works in %s (%.0f works/sec) | %d errors | %,d Solr docs",
			total, formatDuration(elapsedSec), worksPerSec, totalWorksErrored.get(), totalSolrDocsAdded.get()
		);
		logger.info(msg);
		logEntry.addNote(msg);

		if (!perWorkerCounts.isEmpty()) {
			StringBuilder sb = new StringBuilder("Per-worker breakdown: ");
			perWorkerCounts.forEach((id, count) ->
				sb.append("Worker-").append(id).append("=").append(count.get()).append(" ")
			);
			logger.info(sb.toString());
			logEntry.addNote(sb.toString());
		}
	}

	public long getTotalWorksProcessed() { return totalWorksProcessed.get(); }
	public long getTotalWorksErrored() { return totalWorksErrored.get(); }
	public long getTotalWorksToIndex() { return totalWorksToIndex; }
}
