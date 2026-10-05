package com.bws.aspen.parallel_reindexer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration for the parallel reindexer plugin.
 * Reads from parallel_reindexer.properties if present, otherwise uses sensible defaults.
 */
public class ParallelReindexConfig {

	// How many GroupedWorkIndexer worker threads to run
	private int workerThreadCount = 4;

	// How many threads the shared Solr ConcurrentUpdateHttp2SolrClient uses internally
	private int solrClientThreadCount = 4;

	// Queue size for the ConcurrentUpdateHttp2SolrClient (documents buffered before blocking)
	private int solrQueueSize = 200;

	// How many work IDs to pre-fetch into the shared BlockingQueue at a time
	private int workQueueCapacity = 10000;

	// How often (in number of works per worker) to do an intermediate Solr commit
	// The actual commit interval across all threads = this * workerThreadCount
	private int commitIntervalPerWorker = 5000;

	// The batch size for fetching work IDs from the database (LIMIT per fetch)
	private int dbFetchBatchSize = 50000;

	// JDBC connection pool size (one connection per worker + 1 for the coordinator)
	// Derived from workerThreadCount, not user-configurable
	private int dbPoolSize;

	// Whether to log per-worker statistics
	private boolean perWorkerStats = true;

	// Progress reporting interval (number of total works processed)
	private int progressReportInterval = 10000;

	public ParallelReindexConfig() {
		this.dbPoolSize = workerThreadCount + 1;
	}

	/**
	 * Load configuration from a properties file. Missing keys keep their defaults.
	 */
	public static ParallelReindexConfig load(String configDir) {
		ParallelReindexConfig config = new ParallelReindexConfig();
		File propsFile = new File(configDir, "parallel_reindexer.properties");
		if (!propsFile.exists()) {
			// Try current directory
			propsFile = new File("parallel_reindexer.properties");
		}
		if (propsFile.exists()) {
			Properties props = new Properties();
			try (FileInputStream fis = new FileInputStream(propsFile)) {
				props.load(fis);
			} catch (IOException e) {
				System.err.println("Warning: could not read " + propsFile.getAbsolutePath() + ": " + e.getMessage());
				return config;
			}
			config.workerThreadCount = getInt(props, "workerThreadCount", config.workerThreadCount);
			config.solrClientThreadCount = getInt(props, "solrClientThreadCount", config.solrClientThreadCount);
			config.solrQueueSize = getInt(props, "solrQueueSize", config.solrQueueSize);
			config.workQueueCapacity = getInt(props, "workQueueCapacity", config.workQueueCapacity);
			config.commitIntervalPerWorker = getInt(props, "commitIntervalPerWorker", config.commitIntervalPerWorker);
			config.dbFetchBatchSize = getInt(props, "dbFetchBatchSize", config.dbFetchBatchSize);
			config.perWorkerStats = getBoolean(props, "perWorkerStats", config.perWorkerStats);
			config.progressReportInterval = getInt(props, "progressReportInterval", config.progressReportInterval);
		}
		config.dbPoolSize = config.workerThreadCount + 1;
		return config;
	}

	private static int getInt(Properties props, String key, int defaultVal) {
		String val = props.getProperty(key);
		if (val == null || val.trim().isEmpty()) return defaultVal;
		try {
			return Integer.parseInt(val.trim());
		} catch (NumberFormatException e) {
			System.err.println("Warning: invalid integer for " + key + ": " + val + ", using default " + defaultVal);
			return defaultVal;
		}
	}

	private static boolean getBoolean(Properties props, String key, boolean defaultVal) {
		String val = props.getProperty(key);
		if (val == null || val.trim().isEmpty()) return defaultVal;
		return Boolean.parseBoolean(val.trim());
	}

	// Getters
	public int getWorkerThreadCount() { return workerThreadCount; }
	public int getSolrClientThreadCount() { return solrClientThreadCount; }
	public int getSolrQueueSize() { return solrQueueSize; }
	public int getWorkQueueCapacity() { return workQueueCapacity; }
	public int getCommitIntervalPerWorker() { return commitIntervalPerWorker; }
	public int getDbFetchBatchSize() { return dbFetchBatchSize; }
	public int getDbPoolSize() { return dbPoolSize; }
	public boolean isPerWorkerStats() { return perWorkerStats; }
	public int getProgressReportInterval() { return progressReportInterval; }

	@Override
	public String toString() {
		return "ParallelReindexConfig{" +
			"workerThreadCount=" + workerThreadCount +
			", solrClientThreadCount=" + solrClientThreadCount +
			", solrQueueSize=" + solrQueueSize +
			", workQueueCapacity=" + workQueueCapacity +
			", commitIntervalPerWorker=" + commitIntervalPerWorker +
			", dbFetchBatchSize=" + dbFetchBatchSize +
			", dbPoolSize=" + dbPoolSize +
			", progressReportInterval=" + progressReportInterval +
			'}';
	}
}
