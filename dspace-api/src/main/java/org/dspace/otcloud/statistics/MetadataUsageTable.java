/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.statistics;

import java.util.List;

/**
 * A page of usage aggregated by metadata value, together with the information needed to judge how
 * complete the aggregation is.
 *
 * <p>Because the usage statistics core does not store item metadata, this report is built by taking
 * the most-viewed items in scope and grouping them by their metadata values. That set of items is
 * capped, so a caller has to be able to tell whether the cap was reached: {@link #isTruncated()}
 * says so outright, and {@link #getItemsConsidered()} says how many items went into the figures.</p>
 */
public class MetadataUsageTable {

    private final List<MetadataUsageRow> rows;
    private final long totalRows;
    private final long itemsConsidered;
    private final boolean truncated;

    /**
     * @param rows            the rows on the requested page
     * @param totalRows       the total number of distinct metadata values found
     * @param itemsConsidered how many items contributed to the aggregation
     * @param truncated       true when the item cap was reached, so figures are a lower bound
     */
    public MetadataUsageTable(List<MetadataUsageRow> rows, long totalRows, long itemsConsidered,
                              boolean truncated) {
        this.rows = rows;
        this.totalRows = totalRows;
        this.itemsConsidered = itemsConsidered;
        this.truncated = truncated;
    }

    public List<MetadataUsageRow> getRows() {
        return rows;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public long getItemsConsidered() {
        return itemsConsidered;
    }

    /**
     * @return true when more items matched than were aggregated, meaning every count is a lower
     *         bound rather than an exact total
     */
    public boolean isTruncated() {
        return truncated;
    }
}
