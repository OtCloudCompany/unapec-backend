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
 * One page of {@link StatBucket} results together with the total number of distinct buckets that
 * matched the query.
 *
 * <p>The total is obtained from the Solr JSON Facet API {@code numBuckets} statistic, so paging
 * through a large result set never requires materialising every bucket.</p>
 */
public class StatBucketPage {

    private final List<StatBucket> buckets;
    private final long totalBuckets;

    /**
     * @param buckets      the buckets on the requested page
     * @param totalBuckets the total number of distinct buckets matching the query
     */
    public StatBucketPage(List<StatBucket> buckets, long totalBuckets) {
        this.buckets = buckets;
        this.totalBuckets = totalBuckets;
    }

    public List<StatBucket> getBuckets() {
        return buckets;
    }

    public long getTotalBuckets() {
        return totalBuckets;
    }
}
