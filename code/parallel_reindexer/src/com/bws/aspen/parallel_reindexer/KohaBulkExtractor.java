package com.bws.aspen.parallel_reindexer;

import com.turning_leaf_technologies.strings.AspenStringUtils;
import org.apache.logging.log4j.Logger;
import org.marc4j.MarcJsonWriter;
import org.marc4j.MarcXmlReader;
import org.marc4j.marc.DataField;
import org.marc4j.marc.MarcFactory;
import org.marc4j.marc.Record;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.BlockingQueue;
import java.util.zip.CRC32;

/**
 * Phase 1 of Turbo Migration: Bulk extract from Koha database.
 *
 * Runs a single streaming JOIN query against Koha that returns all bibs + items
 * in one pass, then assembles MARC records with 952 item fields (matching the
 * exact subfield mapping from KohaExportMain.updateBibRecord) and pushes them
 * into a BlockingQueue as MarcBatch objects for parallel loading.
 *
 * Replaces 940k individual SELECTs with 1 streaming query.
 */
public class KohaBulkExtractor implements Runnable {

	private final Connection kohaConn;
	private final BlockingQueue<MarcBatch> batchQueue;
	private final int numWorkers;
	private final int batchSize;
	private final MigrationProgressTracker tracker;
	private final Logger logger;
	private final MarcFactory marcFactory = MarcFactory.newInstance();
	private final CRC32 checksumCalculator = new CRC32();

	public KohaBulkExtractor(Connection kohaConn, BlockingQueue<MarcBatch> batchQueue,
							 int numWorkers, int batchSize,
							 MigrationProgressTracker tracker, Logger logger) {
		this.kohaConn = kohaConn;
		this.batchQueue = batchQueue;
		this.numWorkers = numWorkers;
		this.batchSize = batchSize;
		this.tracker = tracker;
		this.logger = logger;
	}

	@Override
	public void run() {
		Thread.currentThread().setName("koha-bulk-extractor");
		logger.info("KohaBulkExtractor starting");

		try {
			extractAll();
		} catch (Exception e) {
			logger.error("KohaBulkExtractor fatal error", e);
			tracker.recordError("extractor", e);
		} finally {
			// Send poison pills to signal workers to shut down
			for (int i = 0; i < numWorkers; i++) {
				try {
					batchQueue.put(MarcBatch.POISON_PILL);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			logger.info("KohaBulkExtractor finished, sent " + numWorkers + " poison pills");
		}
	}

	private void extractAll() throws SQLException, InterruptedException {
		// Single streaming JOIN: all bibs with their items in one query
		String sql =
			"SELECT bm.biblionumber, bm.metadata, " +
			"  i.itemnumber, i.withdrawn, i.itemlost, i.cn_source, i.materials, i.damaged, " +
			"  i.restricted, i.cn_sort, i.notforloan, i.ccode, i.homebranch, i.holdingbranch, " +
			"  i.location, i.dateaccessioned, i.booksellerid, i.coded_location_qualifier, " +
			"  i.price, i.enumchron, i.stocknumber, i.stack, i.issues AS item_issues, " +
			"  i.renewals, i.itemcallnumber, i.barcode, i.onloan, i.datelastseen, " +
			"  i.datelastborrowed, i.copynumber, i.uri, i.replacementprice, " +
			"  i.replacementpricedate, i.itype, i.itemnotes, i.itemnotes_nonpublic, " +
			"  iss.date_due " +
			"FROM biblio_metadata bm " +
			"LEFT JOIN items i ON bm.biblionumber = i.biblionumber " +
			"LEFT JOIN issues iss ON i.itemnumber = iss.itemnumber " +
			"ORDER BY bm.biblionumber";

		PreparedStatement stmt = kohaConn.prepareStatement(sql,
			ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
		stmt.setFetchSize(Integer.MIN_VALUE); // MySQL streaming mode

		logger.info("Executing bulk extraction query...");
		ResultSet rs = stmt.executeQuery();
		logger.info("Query started, streaming results...");

		MarcBatch currentBatch = new MarcBatch(batchSize);
		long currentBibId = -1;
		String currentMarcXml = null;
		Record currentRecord = null;
		int itemCountForCurrentBib = 0;
		int totalBibs = 0;
		int totalItems = 0;

		while (rs.next()) {
			long bibId = rs.getLong("biblionumber");
			String metadata = rs.getString("metadata");

			if (bibId != currentBibId) {
				// Finished previous bib — flush it
				if (currentRecord != null) {
					addRecordToBatch(currentBatch, String.valueOf(currentBibId), currentRecord);
					totalBibs++;
					totalItems += itemCountForCurrentBib;

					if (currentBatch.size() >= batchSize) {
						batchQueue.put(currentBatch);
						tracker.recordExtracted(currentBatch.size(), totalItems);
						totalItems = 0;
						currentBatch = new MarcBatch(batchSize);
					}
				}

				// Start new bib
				currentBibId = bibId;
				currentMarcXml = metadata;
				currentRecord = parseMarcXml(currentMarcXml);
				itemCountForCurrentBib = 0;

				if (currentRecord == null) {
					logger.warn("Failed to parse MARC for biblionumber " + bibId + ", skipping");
					continue;
				}

				// Skip authority records (type 'z')
				if (currentRecord.getLeader().getTypeOfRecord() == 'z') {
					currentRecord = null;
					continue;
				}
			}

			// Add item to current record (if there is an item — LEFT JOIN may have nulls)
			if (currentRecord != null && !rs.wasNull() && rs.getString("itemnumber") != null) {
				addItemToRecord(currentRecord, rs);
				itemCountForCurrentBib++;
			}
		}

		// Flush last bib
		if (currentRecord != null) {
			addRecordToBatch(currentBatch, String.valueOf(currentBibId), currentRecord);
			totalBibs++;
			totalItems += itemCountForCurrentBib;
		}

		// Flush last batch
		if (currentBatch.size() > 0) {
			batchQueue.put(currentBatch);
			tracker.recordExtracted(currentBatch.size(), totalItems);
		}

		rs.close();
		stmt.close();
		logger.info("Extraction complete: " + totalBibs + " bibs");
	}

	private Record parseMarcXml(String marcXml) {
		try {
			String cleaned = AspenStringUtils.stripNonValidXMLCharacters(marcXml);
			MarcXmlReader reader = new MarcXmlReader(
				new ByteArrayInputStream(cleaned.getBytes(StandardCharsets.UTF_8)));
			if (reader.hasNext()) {
				return reader.next();
			}
		} catch (Exception e) {
			logger.warn("Error parsing MARC XML: " + e.getMessage());
		}
		return null;
	}

	/**
	 * Add item data as MARC 952 field — exact subfield mapping from
	 * KohaExportMain.updateBibRecord (lines 1763-1801).
	 */
	private void addItemToRecord(Record record, ResultSet rs) throws SQLException {
		DataField itemField = marcFactory.newDataField("952", ' ', ' ');
		addSubfield(itemField, '0', rs.getString("withdrawn"));
		addSubfield(itemField, '1', rs.getString("itemlost"));
		addSubfield(itemField, '2', rs.getString("cn_source"));
		addSubfield(itemField, '3', rs.getString("materials"));
		addSubfield(itemField, '4', rs.getString("damaged"));
		addSubfield(itemField, '5', rs.getString("restricted"));
		addSubfield(itemField, '6', rs.getString("cn_sort"));
		addSubfield(itemField, '7', rs.getString("notforloan"));
		addSubfield(itemField, '8', rs.getString("ccode"));
		addSubfield(itemField, '9', rs.getString("itemnumber"));
		addSubfield(itemField, 'a', rs.getString("homebranch"));
		addSubfield(itemField, 'b', rs.getString("holdingbranch"));
		addSubfield(itemField, 'c', rs.getString("location"));
		addSubfield(itemField, 'd', rs.getString("dateaccessioned"));
		addSubfield(itemField, 'e', rs.getString("booksellerid"));
		addSubfield(itemField, 'f', rs.getString("coded_location_qualifier"));
		addSubfield(itemField, 'g', rs.getString("price"));
		addSubfield(itemField, 'h', rs.getString("enumchron"));
		addSubfield(itemField, 'i', rs.getString("stocknumber"));
		addSubfield(itemField, 'j', rs.getString("stack"));
		addSubfield(itemField, 'k', rs.getString("date_due")); // non-standard, added by Aspen
		addSubfield(itemField, 'l', rs.getString("item_issues"));
		addSubfield(itemField, 'm', rs.getString("renewals"));
		addSubfield(itemField, 'n', rs.getString("renewals"));
		addSubfield(itemField, 'o', rs.getString("itemcallnumber"));
		addSubfield(itemField, 'p', rs.getString("barcode"));
		addSubfield(itemField, 'q', rs.getString("onloan"));
		addSubfield(itemField, 'r', rs.getString("datelastseen"));
		addSubfield(itemField, 's', rs.getString("datelastborrowed"));
		addSubfield(itemField, 't', rs.getString("copynumber"));
		addSubfield(itemField, 'u', rs.getString("uri"));
		addSubfield(itemField, 'v', rs.getString("replacementprice"));
		addSubfield(itemField, 'w', rs.getString("replacementpricedate"));
		addSubfield(itemField, 'y', rs.getString("itype"));
		addSubfield(itemField, 'z', rs.getString("itemnotes"));
		record.addVariableField(itemField);
	}

	private void addSubfield(DataField field, char code, String data) {
		if (data != null) {
			field.addSubfield(marcFactory.newSubfield(code, data));
		}
	}

	/**
	 * Convert MARC record to JSON bytes + checksum, and add to the batch.
	 * This matches the format used by GroupedWorkIndexer.saveMarcRecordToDatabase().
	 */
	private void addRecordToBatch(MarcBatch batch, String ilsId, Record record) {
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			MarcJsonWriter jsonWriter = new MarcJsonWriter(baos);
			jsonWriter.write(record);
			jsonWriter.close();
			byte[] marcJsonBytes = baos.toByteArray();

			checksumCalculator.reset();
			checksumCalculator.update(marcJsonBytes);
			long checksum = checksumCalculator.getValue();

			batch.add(ilsId, record, marcJsonBytes, checksum);
		} catch (Exception e) {
			logger.warn("Error converting MARC to JSON for " + ilsId + ": " + e.getMessage());
			tracker.recordError("marc-json-" + ilsId, e);
		}
	}
}
