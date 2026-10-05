package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.logging.BaseIndexingLogEntry;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe progress tracker for the Turbo Migration pipeline.
 * Tracks extraction, loading, and grouping phases separately.
 */
public class MigrationProgressTracker {

	private final AtomicLong bibsExtracted = new AtomicLong(0);
	private final AtomicLong itemsExtracted = new AtomicLong(0);
	private final AtomicLong recordsLoaded = new AtomicLong(0);
	private final AtomicLong recordsGrouped = new AtomicLong(0);
	private final AtomicLong errors = new AtomicLong(0);
	private final AtomicLong batchesProcessed = new AtomicLong(0);

	private final long totalBibs; // 0 if unknown
	private final int reportInterval;
	private final Logger logger;
	private final BaseIndexingLogEntry logEntry;
	private final long startTimeMs;

	public MigrationProgressTracker(long totalBibs, int reportInterval, Logger logger, BaseIndexingLogEntry logEntry) {
		this.totalBibs = totalBibs;
		this.reportInterval = reportInterval;
		this.logger = logger;
		this.logEntry = logEntry;
		this.startTimeMs = System.currentTimeMillis();
	}

	public void recordExtracted(int bibCount, int itemCount) {
		long total = bibsExtracted.addAndGet(bibCount);
		itemsExtracted.addAndGet(itemCount);
		if (total % reportInterval < bibCount) {
			reportExtractionProgress(total);
		}
	}

	public void recordBatchLoaded(int count) {
		long total = recordsLoaded.addAndGet(count);
		batchesProcessed.incrementAndGet();
		if (total % reportInterval < count) {
			reportLoadProgress(total);
		}
	}

	public void recordBatchGrouped(int count) {
		long total = recordsGrouped.addAndGet(count);
		if (total % reportInterval < count) {
			reportGroupProgress(total);
		}
	}

	public void recordError(String context, Exception e) {
		errors.incrementAndGet();
		logger.error("Migration error [" + context + "]", e);
	}

	private void reportExtractionProgress(long total) {
		double elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0;
		double rate = elapsedSec > 0 ? total / elapsedSec : 0;
		String pct = totalBibs > 0 ? String.format(" (%.1f%%)", total * 100.0 / totalBibs) : "";
		String msg = String.format("Extract: %,d bibs%s, %,d items | %.0f bibs/sec",
			total, pct, itemsExtracted.get(), rate);
		logger.info(msg);
	}

	private void reportLoadProgress(long total) {
		double elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0;
		double rate = elapsedSec > 0 ? total / elapsedSec : 0;
		String msg = String.format("Load: %,d records upserted (%,d batches) | %.0f rec/sec",
			total, batchesProcessed.get(), rate);
		logger.info(msg);
	}

	private void reportGroupProgress(long total) {
		double elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0;
		double rate = elapsedSec > 0 ? total / elapsedSec : 0;
		String msg = String.format("Group: %,d records grouped | %.0f rec/sec", total, rate);
		logger.info(msg);
	}

	public void reportFinal() {
		double elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000.0;
		String duration = formatDuration(elapsedSec);
		String msg = String.format(
			"MIGRATION COMPLETE: %,d bibs extracted (%,d items), %,d loaded, %,d grouped in %s | %d errors",
			bibsExtracted.get(), itemsExtracted.get(), recordsLoaded.get(), recordsGrouped.get(),
			duration, errors.get()
		);
		logger.info(msg);
		logEntry.addNote(msg);
	}

	private String formatDuration(double seconds) {
		long h = (long) (seconds / 3600);
		long m = (long) ((seconds % 3600) / 60);
		long s = (long) (seconds % 60);
		if (h > 0) return String.format("%dh %dm %ds", h, m, s);
		if (m > 0) return String.format("%dm %ds", m, s);
		return String.format("%ds", s);
	}

	// Getters
	public long getBibsExtracted() { return bibsExtracted.get(); }
	public long getRecordsLoaded() { return recordsLoaded.get(); }
	public long getRecordsGrouped() { return recordsGrouped.get(); }
	public long getErrors() { return errors.get(); }
}
