/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.statistics;

/**
 * Usage aggregated against one value of a metadata field, for example one author or one department.
 */
public class MetadataUsageRow {

    private final String value;
    private long views;
    private long downloads;
    private long items;

    /**
     * @param value the metadata value this row aggregates
     */
    public MetadataUsageRow(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public long getViews() {
        return views;
    }

    public void addViews(long delta) {
        this.views += delta;
    }

    public long getDownloads() {
        return downloads;
    }

    public void addDownloads(long delta) {
        this.downloads += delta;
    }

    /**
     * @return how many distinct items carrying this value contributed to the counts
     */
    public long getItems() {
        return items;
    }

    public void addItem() {
        this.items++;
    }
}
