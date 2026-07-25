/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.statistics;

/**
 * A single aggregation bucket returned by a usage statistics facet query: the facet value plus the
 * number of views and, where applicable, the number of downloads attributed to it.
 */
public class StatBucket {

    private final String value;
    private long views;
    private long downloads;

    /**
     * @param value the facet value this bucket aggregates (an object UUID, a country code, a
     *              metadata value, ...)
     * @param views the number of views counted for the value
     */
    public StatBucket(String value, long views) {
        this.value = value;
        this.views = views;
    }

    public String getValue() {
        return value;
    }

    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }

    public long getDownloads() {
        return downloads;
    }

    public void setDownloads(long downloads) {
        this.downloads = downloads;
    }
}
