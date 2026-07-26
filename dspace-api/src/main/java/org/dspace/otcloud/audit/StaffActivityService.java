/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.audit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.dspace.otcloud.statistics.StatDateRange;
import org.dspace.services.ConfigurationService;
import org.dspace.statistics.SolrClientFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Aggregate reporting over the audit trail: how much work each staff member did in a period.
 *
 * <p>The DSpace 10 audit feature this builds on exposes only a raw event browser - fetch one event,
 * fetch all events, or fetch the events for a single object. It cannot answer "how many items did
 * this person upload or edit last month", which is the question a repository administrator actually
 * asks. This service adds that, without modifying the upstream audit classes.</p>
 *
 * <p>No change to the audit Solr schema is needed: {@code eperson_uuid}, {@code timeStamp},
 * {@code event_type} and {@code subject_type} are all already indexed. The whole report is produced
 * by a single Solr JSON Facet query that buckets by staff member and, within each bucket, counts
 * distinct objects per kind of change.</p>
 *
 * <p>Counts are of distinct objects rather than audit rows, because the audit core writes one row
 * per changed metadata value: an edit touching five fields writes five rows, and counting rows
 * would overstate the work by fivefold.</p>
 *
 * <p>The distinct counts use Solr's {@code unique()} aggregation, which is exact for small sets and
 * switches to a HyperLogLog estimate above roughly a hundred values. Figures in the low hundreds and
 * beyond should therefore be read as very close rather than exact. {@code totalEvents} is always an
 * exact row count.</p>
 */
public class StaffActivityService {

    private static final Logger log = LogManager.getLogger(StaffActivityService.class);

    /**
     * Solr field holding the user responsible for a change.
     */
    private static final String EPERSON_FIELD = "eperson_uuid";

    /**
     * Solr field holding the time a change happened.
     */
    private static final String TIMESTAMP_FIELD = "timeStamp";

    @Autowired
    protected ConfigurationService configurationService;

    /**
     * Built by whichever {@link SolrClientFactory} the running environment provides: the HTTP one in
     * a real deployment, the embedded one under test. Depending on the interface rather than on
     * {@code HttpSolrClientFactory} keeps this service wireable in both.
     */
    @Autowired
    protected SolrClientFactory solrClientFactory;

    private SolrClient solr;

    /**
     * @return true when the audit system is switched on; every report is empty when it is not
     */
    public boolean isAuditEnabled() {
        return configurationService.getBooleanProperty("audit.enabled", false);
    }

    /**
     * Return one page of staff activity for a reporting period, ordered by descending activity
     * volume.
     *
     * @param range  the reporting period; either bound may be open
     * @param offset zero-based offset into the list of staff members
     * @param limit  maximum number of staff members to return
     * @return a page of rows, empty when auditing is disabled or no activity was recorded
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    public StaffActivityPage staffActivity(StatDateRange range, int offset, int limit)
        throws SolrServerException, IOException {

        if (!isAuditEnabled()) {
            return new StaffActivityPage(Collections.emptyList(), 0);
        }

        SolrQuery solrQuery = new SolrQuery("*:*");
        solrQuery.setRows(0);
        // Changes made by an unauthenticated process carry no eperson and cannot be attributed.
        solrQuery.addFilterQuery(EPERSON_FIELD + ":[* TO *]");

        if (range != null) {
            String timeFilter = range.toSolrFilterQuery(TIMESTAMP_FIELD);
            if (timeFilter != null) {
                solrQuery.addFilterQuery(timeFilter);
            }
        }

        solrQuery.set("json.facet", staffFacet(offset, limit));

        QueryResponse response = query(solrQuery);
        if (response == null) {
            return new StaffActivityPage(Collections.emptyList(), 0);
        }
        return readStaffActivity(response);
    }

    /**
     * Build the JSON Facet request: bucket by staff member, and inside each bucket count distinct
     * objects for each kind of change.
     *
     * <p>Built as a map and serialised, rather than assembled as a string, so that quoting stays
     * correct without a thicket of escapes.</p>
     *
     * @param offset zero-based offset into the list of staff members
     * @param limit  maximum number of staff members to return
     * @return the JSON Facet request body
     * @throws IOException if the facet request cannot be serialised
     */
    private String staffFacet(int offset, int limit) throws IOException {
        Map<String, Object> subFacets = new LinkedHashMap<>();
        subFacets.put("itemsCreated", distinctSubjects("event_type:CREATE AND subject_type:ITEM"));
        subFacets.put("itemsArchived", distinctSubjects("event_type:INSTALL AND subject_type:ITEM"));
        subFacets.put("itemsEdited", distinctSubjects("event_type:MODIFY_METADATA AND subject_type:ITEM"));
        subFacets.put("itemsDeleted", distinctSubjects("event_type:DELETE AND subject_type:ITEM"));
        subFacets.put("bitstreamsAdded", distinctSubjects("event_type:CREATE AND subject_type:BITSTREAM"));

        Map<String, Object> staff = new LinkedHashMap<>();
        staff.put("type", "terms");
        staff.put("field", EPERSON_FIELD);
        staff.put("limit", limit);
        staff.put("offset", offset);
        staff.put("mincount", 1);
        staff.put("numBuckets", true);
        staff.put("sort", "count desc");
        staff.put("facet", subFacets);

        return new ObjectMapper().writeValueAsString(Map.of("staff", staff));
    }

    /**
     * Build a sub-facet that counts the distinct subjects of the rows matching a query.
     *
     * @param query the Solr query selecting the rows to count over
     * @return the sub-facet definition
     */
    private Map<String, Object> distinctSubjects(String query) {
        Map<String, Object> facet = new LinkedHashMap<>();
        facet.put("type", "query");
        facet.put("q", query);
        facet.put("facet", Map.of("objects", "unique(subject_uuid)"));
        return facet;
    }

    /**
     * Read the faceted response into staff activity rows.
     *
     * @param response the Solr response
     * @return the page of rows the response describes
     */
    @SuppressWarnings("unchecked")
    private StaffActivityPage readStaffActivity(QueryResponse response) {
        Object facets = response.getResponse().get("facets");
        if (!(facets instanceof NamedList)) {
            return new StaffActivityPage(Collections.emptyList(), 0);
        }
        Object staff = ((NamedList<Object>) facets).get("staff");
        if (!(staff instanceof NamedList)) {
            return new StaffActivityPage(Collections.emptyList(), 0);
        }
        NamedList<Object> staffFacet = (NamedList<Object>) staff;

        Object rawBuckets = staffFacet.get("buckets");
        if (!(rawBuckets instanceof List)) {
            return new StaffActivityPage(Collections.emptyList(), 0);
        }

        List<StaffActivityRow> rows = new ArrayList<>();
        for (Object rawBucket : (List<Object>) rawBuckets) {
            if (!(rawBucket instanceof NamedList)) {
                continue;
            }
            NamedList<Object> bucket = (NamedList<Object>) rawBucket;
            UUID epersonUuid = toUuid(bucket.get("val"));
            if (epersonUuid == null) {
                continue;
            }

            StaffActivityRow row = new StaffActivityRow(epersonUuid);
            row.setTotalEvents(asLong(bucket.get("count")));
            row.setItemsCreated(distinctCount(bucket, "itemsCreated"));
            row.setItemsArchived(distinctCount(bucket, "itemsArchived"));
            row.setItemsEdited(distinctCount(bucket, "itemsEdited"));
            row.setItemsDeleted(distinctCount(bucket, "itemsDeleted"));
            row.setBitstreamsAdded(distinctCount(bucket, "bitstreamsAdded"));
            rows.add(row);
        }

        long totalStaff = rows.size();
        Object numBuckets = staffFacet.get("numBuckets");
        if (numBuckets instanceof Number) {
            totalStaff = ((Number) numBuckets).longValue();
        }
        return new StaffActivityPage(rows, totalStaff);
    }

    /**
     * Read a named distinct-object count out of a staff bucket.
     *
     * @param bucket the staff bucket
     * @param name   the sub-facet name
     * @return the distinct object count, or zero when absent
     */
    @SuppressWarnings("unchecked")
    private long distinctCount(NamedList<Object> bucket, String name) {
        Object section = bucket.get(name);
        if (!(section instanceof NamedList)) {
            return 0;
        }
        return asLong(((NamedList<Object>) section).get("objects"));
    }

    private long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Run a query against the audit core.
     *
     * @param solrQuery the query to run
     * @return the Solr response, or null when the audit core is not reachable
     * @throws SolrServerException if the Solr query fails
     * @throws IOException         if the Solr query fails
     */
    protected QueryResponse query(SolrQuery solrQuery) throws SolrServerException, IOException {
        SolrClient client = getSolr();
        if (client == null) {
            return null;
        }
        return client.query(solrQuery);
    }

    /**
     * Lazily open a connection to the audit core, using the same configuration property the
     * upstream audit service uses.
     *
     * @return the audit core client, or null when no audit core is configured
     */
    protected SolrClient getSolr() {
        if (solr == null) {
            String server = configurationService.getProperty("audit.solr.server");
            if (StringUtils.isBlank(server)) {
                log.warn("audit.solr.server is not configured; staff activity reports are unavailable");
                return null;
            }
            solr = solrClientFactory.getClient(server);
        }
        return solr;
    }
}
