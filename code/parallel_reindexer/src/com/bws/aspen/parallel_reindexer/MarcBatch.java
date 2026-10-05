package com.bws.aspen.parallel_reindexer;

import org.marc4j.marc.Record;

import java.util.ArrayList;
import java.util.List;

/**
 * A batch of assembled MARC records ready for bulk loading into Aspen.
 * Each entry contains the biblionumber (ilsId) and the fully-assembled
 * MARC record (with items already appended as 952 fields).
 *
 * Used as the transfer object between KohaBulkExtractor (producer)
 * and BulkLoadWorker (consumer) threads.
 */
public final class MarcBatch {

	/** Sentinel batch placed in the queue to signal workers to shut down. */
	public static final MarcBatch POISON_PILL = new MarcBatch();

	private final List<Entry> entries;

	private MarcBatch() {
		this.entries = null; // poison pill
	}

	public MarcBatch(int capacity) {
		this.entries = new ArrayList<>(capacity);
	}

	public void add(String ilsId, Record marcRecord, byte[] marcJsonBytes, long checksum) {
		entries.add(new Entry(ilsId, marcRecord, marcJsonBytes, checksum));
	}

	public List<Entry> getEntries() {
		return entries;
	}

	public int size() {
		return entries == null ? 0 : entries.size();
	}

	public boolean isPoisonPill() {
		return this == POISON_PILL;
	}

	/**
	 * One assembled MARC record ready for bulk loading.
	 */
	public static final class Entry {
		private final String ilsId;
		private final Record marcRecord;
		private final byte[] marcJsonBytes;
		private final long checksum;

		Entry(String ilsId, Record marcRecord, byte[] marcJsonBytes, long checksum) {
			this.ilsId = ilsId;
			this.marcRecord = marcRecord;
			this.marcJsonBytes = marcJsonBytes;
			this.checksum = checksum;
		}

		public String getIlsId() { return ilsId; }
		public Record getMarcRecord() { return marcRecord; }
		public byte[] getMarcJsonBytes() { return marcJsonBytes; }
		public long getChecksum() { return checksum; }
	}
}
