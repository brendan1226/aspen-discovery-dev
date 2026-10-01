package com.bws.aspen.parallel_reindexer;

/**
 * Represents one grouped work to be indexed.
 * Immutable value object passed through the work queue.
 */
public final class WorkUnit {
	private final long id;
	private final String permanentId;
	private final String groupingCategory;
	private final Long dateUpdated; // null if never set

	/** Sentinel value placed in the queue to signal workers to shut down. */
	public static final WorkUnit POISON_PILL = new WorkUnit(-1, "__POISON__", "__POISON__", null);

	public WorkUnit(long id, String permanentId, String groupingCategory, Long dateUpdated) {
		this.id = id;
		this.permanentId = permanentId;
		this.groupingCategory = groupingCategory;
		this.dateUpdated = dateUpdated;
	}

	public long getId() { return id; }
	public String getPermanentId() { return permanentId; }
	public String getGroupingCategory() { return groupingCategory; }
	public Long getDateUpdated() { return dateUpdated; }

	public boolean isPoisonPill() { return this == POISON_PILL; }

	@Override
	public String toString() {
		return "WorkUnit{id=" + id + ", permanentId='" + permanentId + "'}";
	}
}
