/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.statistics;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.MetadataValue;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Aggregates usage against any metadata field, answering questions such as which authors,
 * departments or publication types attract the most traffic.
 *
 * <p>This is the one report that cannot be produced by the usage statistics core alone: that core
 * records which object was viewed but nothing about the object's metadata. The aggregation is
 * therefore done in two steps - ask Solr for views per item in scope, then group those items by the
 * requested metadata field.</p>
 *
 * <p>The cost of the second step is proportional to the number of items involved, so the number of
 * items aggregated is capped by
 * {@code otcloud-statistics.metadata-table.max-items} (default 5000). When the cap is reached the
 * resulting table is flagged as truncated and its counts are a lower bound: they cover the most
 * viewed items in scope, which is what a "top authors" style report wants, but they are not
 * repository-wide totals. Callers should surface that distinction rather than hide it.</p>
 */
public class MetadataUsageService {

    /**
     * Default ceiling on how many items are pulled into one aggregation.
     */
    private static final int DEFAULT_MAX_ITEMS = 5000;

    /**
     * How many items to resolve per batch when grouping by metadata value.
     */
    private static final int ITEM_BATCH_SIZE = 200;

    @Autowired
    protected OTCloudStatisticsService otCloudStatisticsService;

    @Autowired
    protected ItemService itemService;

    @Autowired
    protected ConfigurationService configurationService;

    /**
     * Aggregate views and downloads by the values of a metadata field.
     *
     * @param context       the DSpace context
     * @param container     the community or collection to look within, or null for the whole site
     * @param metadataField the field to group by, in {@code schema.element[.qualifier]} form
     * @param range         the date range to count within
     * @param offset        zero-based offset into the sorted list of metadata values
     * @param limit         maximum number of metadata values to return
     * @return the aggregated table, ordered by descending views
     * @throws SolrServerException      if a Solr query fails
     * @throws IOException              if a Solr query fails
     * @throws SQLException             if an item lookup fails
     * @throws IllegalArgumentException if the metadata field is not a valid field name
     */
    public MetadataUsageTable aggregate(Context context, DSpaceObject container, String metadataField,
                                        StatDateRange range, int offset, int limit)
        throws SolrServerException, IOException, SQLException {

        String[] field = parseField(metadataField);
        int maxItems = configurationService.getIntProperty("otcloud-statistics.metadata-table.max-items",
                                                          DEFAULT_MAX_ITEMS);

        // Step one: views per item, most viewed first, so that a truncated aggregation still
        // describes the items that matter most.
        StatBucketPage itemViews = otCloudStatisticsService.facetTerms(
                otCloudStatisticsService.scopeFilter(container), "id", Constants.ITEM, range, 0, maxItems);

        boolean truncated = itemViews.getTotalBuckets() > itemViews.getBuckets().size();

        // Step two: downloads for the same items, batched so this stays a bounded number of queries.
        Map<String, Long> downloads = downloadsFor(itemViews.getBuckets(), range);

        // Step three: group by metadata value.
        Map<String, MetadataUsageRow> rows = new LinkedHashMap<>();
        long itemsConsidered = 0;
        for (StatBucket bucket : itemViews.getBuckets()) {
            Item item = findItem(context, bucket.getValue());
            if (item == null) {
                continue;
            }
            itemsConsidered++;

            long itemDownloads = downloads.getOrDefault(bucket.getValue(), 0L);
            for (String value : distinctValues(item, field)) {
                MetadataUsageRow row = rows.computeIfAbsent(value, MetadataUsageRow::new);
                row.addViews(bucket.getViews());
                row.addDownloads(itemDownloads);
                row.addItem();
            }
        }

        List<MetadataUsageRow> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparingLong(MetadataUsageRow::getViews).reversed()
                              .thenComparing(MetadataUsageRow::getValue));

        List<MetadataUsageRow> page = new ArrayList<>();
        for (int i = offset; i < sorted.size() && page.size() < limit; i++) {
            page.add(sorted.get(i));
        }

        return new MetadataUsageTable(page, sorted.size(), itemsConsidered, truncated);
    }

    /**
     * Resolve download counts for a set of items, in batches.
     *
     * @param buckets the item buckets to resolve downloads for
     * @param range   the date range to count within
     * @return a map of item UUID to download count
     * @throws SolrServerException if a Solr query fails
     * @throws IOException         if a Solr query fails
     */
    private Map<String, Long> downloadsFor(List<StatBucket> buckets, StatDateRange range)
        throws SolrServerException, IOException {

        Map<String, Long> downloads = new LinkedHashMap<>();
        List<String> batch = new ArrayList<>(ITEM_BATCH_SIZE);
        for (StatBucket bucket : buckets) {
            batch.add(bucket.getValue());
            if (batch.size() == ITEM_BATCH_SIZE) {
                downloads.putAll(otCloudStatisticsService.downloadsForItems(batch, range));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            downloads.putAll(otCloudStatisticsService.downloadsForItems(batch, range));
        }
        return downloads;
    }

    /**
     * Return the distinct, non-blank values an item carries for a metadata field. Values are
     * de-duplicated so that an item repeating the same value does not count twice.
     *
     * @param item  the item to read
     * @param field the parsed schema, element and qualifier
     * @return the distinct values, in the order the item lists them
     */
    private List<String> distinctValues(Item item, String[] field) {
        List<String> values = new ArrayList<>();
        for (MetadataValue metadataValue : itemService.getMetadata(item, field[0], field[1], field[2], Item.ANY)) {
            String value = metadataValue.getValue();
            if (StringUtils.isNotBlank(value) && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private Item findItem(Context context, String uuid) throws SQLException {
        try {
            return itemService.find(context, UUID.fromString(uuid));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Split a metadata field name into schema, element and qualifier.
     *
     * @param metadataField the field name, in {@code schema.element[.qualifier]} form
     * @return a three-element array of schema, element and qualifier; the qualifier may be null
     * @throws IllegalArgumentException if the name does not have two or three parts
     */
    private String[] parseField(String metadataField) {
        if (StringUtils.isBlank(metadataField)) {
            throw new IllegalArgumentException("A metadata field is required");
        }
        String[] parts = metadataField.trim().split("\\.");
        if (parts.length == 2) {
            return new String[] {parts[0], parts[1], null};
        }
        if (parts.length == 3) {
            return new String[] {parts[0], parts[1], parts[2]};
        }
        throw new IllegalArgumentException(
                "Invalid metadata field, expected schema.element or schema.element.qualifier: " + metadataField);
    }
}
