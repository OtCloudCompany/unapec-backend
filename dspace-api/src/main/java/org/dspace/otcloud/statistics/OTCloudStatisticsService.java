/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.statistics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.core.Constants;
import org.dspace.services.ConfigurationService;
import org.dspace.statistics.SolrStatisticsCore;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Aggregation queries against the DSpace usage statistics Solr core, built on the Solr JSON Facet
 * API.
 *
 * <p>This service exists alongside — and deliberately does not modify — the stock
 * {@code SolrLoggerService}. It is the single query entry point for the extended UNAPEC usage
 * reports, so that a future DSpace upgrade only has to preserve this package rather than a set of
 * edits scattered through upstream classes.</p>
 *
 * <p>Using the JSON Facet API rather than legacy facets buys two things the stock statistics code
 * cannot express: {@code numBuckets}, which yields an exact distinct-value total without
 * materialising every bucket, and nested/batched facets, which let a page of objects and their
 * download counts be resolved in a fixed number of round trips instead of one per object.</p>
 */
public class OTCloudStatisticsService {

    /**
     * Value of the Solr {@code statistics_type} field for ordinary view events.
     */
    public static final String STATISTICS_TYPE_VIEW = "view";

    @Autowired
    protected SolrStatisticsCore solrStatisticsCore;

    @Autowired
    protected ConfigurationService configurationService;

    /**
     * Build the Solr filter query that scopes a statistics search to the given container.
     *
     * <p>The usage event documents carry {@code owningComm} as a multi-valued field holding every
     * ancestor community, so a community scope transparently includes all of its sub-communities
     * and their collections.</p>
     *
     * @param container the community, collection or item to scope to, or null for the whole site
     * @return a Solr filter query, or null when the whole site is in scope
     */
    public String scopeFilter(DSpaceObject container) {
        if (container == null) {
            return null;
        }
        if (container instanceof Community) {
            return "owningComm:" + container.getID();
        }
        if (container instanceof Collection) {
            return "owningColl:" + container.getID();
        }
        if (container instanceof Item) {
            return "owningItem:" + container.getID();
        }
        return null;
    }

    /**
     * Return one page of the most viewed items within a container, each with its download count.
     *
     * <p>This runs exactly two Solr queries regardless of page size: one faceting item view events
     * by object id, and one resolving bitstream download counts for the items on that page.</p>
     *
     * @param container the community or collection to look within, or null for the whole site
     * @param range     the date range to count within
     * @param offset    zero-based bucket offset
     * @param limit     maximum number of items to return
     * @return a page of buckets keyed by item UUID, with views and downloads populated
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    public StatBucketPage topItems(DSpaceObject container, StatDateRange range, int offset, int limit)
        throws SolrServerException, IOException {

        StatBucketPage page = facetTerms(scopeFilter(container), "id", Constants.ITEM, range, offset, limit);

        List<String> itemIds = new ArrayList<>();
        for (StatBucket bucket : page.getBuckets()) {
            itemIds.add(bucket.getValue());
        }

        Map<String, Long> downloads = downloadsForItems(itemIds, range);
        for (StatBucket bucket : page.getBuckets()) {
            bucket.setDownloads(downloads.getOrDefault(bucket.getValue(), 0L));
        }
        return page;
    }

    /**
     * Facet view events on an arbitrary Solr field, returning one page of buckets plus the exact
     * total number of distinct values.
     *
     * @param scopeFilter an additional Solr filter query limiting the scope, or null for none
     * @param field       the Solr field to facet on, for example {@code id}, {@code countryCode}
     * @param dsoType     the DSpace {@link Constants} object type to count, or -1 for any type
     * @param range       the date range to count within
     * @param offset      zero-based bucket offset
     * @param limit       maximum number of buckets to return
     * @return a page of buckets ordered by descending view count
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    public StatBucketPage facetTerms(String scopeFilter, String field, int dsoType, StatDateRange range,
                                     int offset, int limit) throws SolrServerException, IOException {

        SolrQuery solrQuery = baseQuery(scopeFilter, dsoType, range);

        String jsonFacet = "{\"buckets\":{\"type\":\"terms\",\"field\":\"" + field
            + "\",\"limit\":" + limit
            + ",\"offset\":" + offset
            + ",\"mincount\":1,\"numBuckets\":true,\"sort\":\"count desc\"}}";
        solrQuery.set("json.facet", jsonFacet);

        QueryResponse response = execute(solrQuery);
        if (response == null) {
            return new StatBucketPage(Collections.emptyList(), 0);
        }
        return readBucketPage(response, "buckets");
    }

    /**
     * Resolve the number of bitstream downloads for each of the given items in a single query.
     *
     * @param itemIds the item UUIDs to resolve, as strings
     * @param range   the date range to count within
     * @return a map of item UUID to download count; items with no downloads are absent
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    public Map<String, Long> downloadsForItems(List<String> itemIds, StatDateRange range)
        throws SolrServerException, IOException {

        if (itemIds == null || itemIds.isEmpty()) {
            return Collections.emptyMap();
        }

        SolrQuery solrQuery = baseQuery("owningItem:(" + StringUtils.join(itemIds, " OR ") + ")",
                                        Constants.BITSTREAM, range);

        String jsonFacet = "{\"buckets\":{\"type\":\"terms\",\"field\":\"owningItem\",\"limit\":"
            + itemIds.size() + ",\"mincount\":1}}";
        solrQuery.set("json.facet", jsonFacet);

        QueryResponse response = execute(solrQuery);
        if (response == null) {
            return Collections.emptyMap();
        }

        Map<String, Long> downloads = new HashMap<>();
        for (StatBucket bucket : readBucketPage(response, "buckets").getBuckets()) {
            downloads.put(bucket.getValue(), bucket.getViews());
        }
        return downloads;
    }

    /**
     * Build a time series of view counts bucketed by a calendar interval.
     *
     * @param scopeFilter an additional Solr filter query limiting the scope, or null for none
     * @param dsoType     the DSpace {@link Constants} object type to count, or -1 for any type
     * @param range       the date range to cover; both bounds are required
     * @param gap         the Solr date-math gap, for example {@code +1MONTH}
     * @return an ordered list of buckets whose value is the ISO start instant of each interval
     * @throws SolrServerException      if the Solr query fails
     * @throws IOException              if the Solr query fails
     * @throws IllegalArgumentException if the range is not fully bounded
     */
    public List<StatBucket> timeSeries(String scopeFilter, int dsoType, StatDateRange range, String gap)
        throws SolrServerException, IOException {

        if (range == null || range.getStart() == null || range.getEnd() == null) {
            throw new IllegalArgumentException("A time series requires both a start and an end date");
        }

        // The range itself is expressed inside the JSON facet, so it must not also be applied as a
        // filter query: doing both would clip the first and last interval.
        SolrQuery solrQuery = baseQuery(scopeFilter, dsoType, StatDateRange.allTime());

        String jsonFacet = "{\"series\":{\"type\":\"range\",\"field\":\"time\",\"start\":\""
            + range.startBound() + "\",\"end\":\"" + range.endBound()
            + "\",\"gap\":\"" + gap + "\"}}";
        solrQuery.set("json.facet", jsonFacet);

        QueryResponse response = execute(solrQuery);
        if (response == null) {
            return Collections.emptyList();
        }

        NamedList<Object> series = facetSection(response, "series");
        if (series == null) {
            return Collections.emptyList();
        }
        return readBuckets(series);
    }

    /**
     * Count the total number of matching view events.
     *
     * @param scopeFilter an additional Solr filter query limiting the scope, or null for none
     * @param dsoType     the DSpace {@link Constants} object type to count, or -1 for any type
     * @param range       the date range to count within
     * @return the number of matching events
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    public long total(String scopeFilter, int dsoType, StatDateRange range)
        throws SolrServerException, IOException {

        QueryResponse response = execute(baseQuery(scopeFilter, dsoType, range));
        if (response == null || response.getResults() == null) {
            return 0;
        }
        return response.getResults().getNumFound();
    }

    /**
     * Count the number of distinct values of a field, for example the number of items in a
     * container that have been viewed at least once.
     *
     * @param scopeFilter an additional Solr filter query limiting the scope, or null for none
     * @param field       the Solr field whose distinct values are counted
     * @param dsoType     the DSpace {@link Constants} object type to count, or -1 for any type
     * @param range       the date range to count within
     * @return the number of distinct values
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    public long countDistinct(String scopeFilter, String field, int dsoType, StatDateRange range)
        throws SolrServerException, IOException {
        return facetTerms(scopeFilter, field, dsoType, range, 0, 0).getTotalBuckets();
    }

    /**
     * Build a statistics query carrying the scope, object type, date range and the same default
     * filters the stock DSpace statistics code applies.
     *
     * @param scopeFilter an additional Solr filter query, or null for none
     * @param dsoType     the DSpace {@link Constants} object type, or -1 for any type
     * @param range       the date range to count within
     * @return a query with zero rows requested, ready for a facet to be attached
     */
    protected SolrQuery baseQuery(String scopeFilter, int dsoType, StatDateRange range) {
        SolrQuery solrQuery = new SolrQuery("*:*");
        solrQuery.setRows(0);

        solrQuery.addFilterQuery("statistics_type:" + STATISTICS_TYPE_VIEW);

        if (dsoType >= 0) {
            solrQuery.addFilterQuery("type:" + dsoType);
        }
        if (StringUtils.isNotBlank(scopeFilter)) {
            solrQuery.addFilterQuery(scopeFilter);
        }
        if (range != null) {
            String timeFilter = range.toSolrFilterQuery();
            if (timeFilter != null) {
                solrQuery.addFilterQuery(timeFilter);
            }
        }
        applyDefaultFilters(solrQuery);
        return solrQuery;
    }

    /**
     * Apply the bot and bundle exclusions that the stock statistics queries use, so that the
     * extended reports return counts consistent with the built-in ones.
     *
     * @param solrQuery the query to add filters to
     */
    protected void applyDefaultFilters(SolrQuery solrQuery) {
        if (configurationService.getBooleanProperty("solr-statistics.query.filter.isBot", true)) {
            solrQuery.addFilterQuery("-isBot:true");
        }

        String[] bundles = configurationService.getArrayProperty("solr-statistics.query.filter.bundles");
        if (bundles != null && bundles.length > 0) {
            // Keep documents that have no bundle at all (items, collections, ...) as well as
            // bitstreams in one of the configured bundles.
            StringBuilder bundleQuery = new StringBuilder("-(bundleName:[* TO *]");
            for (int i = 0; i < bundles.length; i++) {
                bundleQuery.append("-bundleName:").append(bundles[i].trim());
                if (i != bundles.length - 1) {
                    bundleQuery.append(" AND ");
                }
            }
            bundleQuery.append(")");
            solrQuery.addFilterQuery(bundleQuery.toString());
        }
    }

    /**
     * Run a query against the statistics core.
     *
     * @param solrQuery the query to run
     * @return the Solr response, or null when no statistics core is configured
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    protected QueryResponse execute(SolrQuery solrQuery) throws SolrServerException, IOException {
        if (solrStatisticsCore.getSolr() == null) {
            return null;
        }
        return solrStatisticsCore.getSolr().query(solrQuery);
    }

    /**
     * Read a named JSON facet section from a Solr response.
     *
     * @param response  the Solr response
     * @param facetName the name the facet was given in the request
     * @return the facet section, or null when the response carries no such facet
     */
    @SuppressWarnings("unchecked")
    protected NamedList<Object> facetSection(QueryResponse response, String facetName) {
        Object facets = response.getResponse().get("facets");
        if (!(facets instanceof NamedList)) {
            return null;
        }
        Object section = ((NamedList<Object>) facets).get(facetName);
        if (!(section instanceof NamedList)) {
            return null;
        }
        return (NamedList<Object>) section;
    }

    /**
     * Read a named JSON facet section into a page of buckets, including the {@code numBuckets}
     * total when the request asked for it.
     *
     * @param response  the Solr response
     * @param facetName the name the facet was given in the request
     * @return the page of buckets, empty when the facet is absent
     */
    protected StatBucketPage readBucketPage(QueryResponse response, String facetName) {
        NamedList<Object> section = facetSection(response, facetName);
        if (section == null) {
            return new StatBucketPage(Collections.emptyList(), 0);
        }

        List<StatBucket> buckets = readBuckets(section);

        long total = buckets.size();
        Object numBuckets = section.get("numBuckets");
        if (numBuckets instanceof Number) {
            total = ((Number) numBuckets).longValue();
        }
        return new StatBucketPage(buckets, total);
    }

    /**
     * Read the {@code buckets} list of a JSON facet section.
     *
     * @param section the facet section
     * @return the buckets it contains, in the order Solr returned them
     */
    @SuppressWarnings("unchecked")
    protected List<StatBucket> readBuckets(NamedList<Object> section) {
        Object rawBuckets = section.get("buckets");
        if (!(rawBuckets instanceof List)) {
            return Collections.emptyList();
        }

        List<StatBucket> buckets = new ArrayList<>();
        for (Object rawBucket : (List<Object>) rawBuckets) {
            if (!(rawBucket instanceof NamedList)) {
                continue;
            }
            NamedList<Object> bucket = (NamedList<Object>) rawBucket;
            Object value = bucket.get("val");
            Object count = bucket.get("count");
            if (value == null) {
                continue;
            }
            long views = count instanceof Number ? ((Number) count).longValue() : 0L;
            buckets.add(new StatBucket(String.valueOf(value), views));
        }
        return buckets;
    }

    /**
     * Convert a list of buckets into an ordered map of value to view count, preserving Solr's
     * ordering.
     *
     * @param buckets the buckets to convert
     * @return an ordered map of bucket value to view count
     */
    public static Map<String, Long> asOrderedMap(List<StatBucket> buckets) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (StatBucket bucket : buckets) {
            map.put(bucket.getValue(), bucket.getViews());
        }
        return map;
    }

    /**
     * Convert a list of UUIDs to their string form for use in a Solr filter.
     *
     * @param uuids the UUIDs to convert
     * @return the string representations, in iteration order
     */
    public static List<String> asStrings(List<UUID> uuids) {
        List<String> strings = new ArrayList<>();
        for (UUID uuid : uuids) {
            strings.add(uuid.toString());
        }
        return strings;
    }
}
