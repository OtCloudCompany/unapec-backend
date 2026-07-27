/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.ItemStatsRest;
import org.dspace.app.rest.model.MetadataUsageRest;
import org.dspace.app.rest.model.RestModel;
import org.dspace.app.rest.model.SearchTermRest;
import org.dspace.app.rest.model.hateoas.ItemStatsResource;
import org.dspace.app.rest.model.hateoas.MetadataUsageResource;
import org.dspace.app.rest.model.hateoas.SearchTermResource;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.DSpaceObjectUtils;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.Site;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.otcloud.statistics.MetadataUsageRow;
import org.dspace.otcloud.statistics.MetadataUsageService;
import org.dspace.otcloud.statistics.MetadataUsageTable;
import org.dspace.otcloud.statistics.OTCloudStatisticsService;
import org.dspace.otcloud.statistics.StatBucket;
import org.dspace.otcloud.statistics.StatBucketPage;
import org.dspace.otcloud.statistics.StatDateRange;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Extended usage statistics endpoints that the stock DSpace {@code usagereports} endpoint does not
 * cover, such as a paged list of the most viewed items within a container.
 *
 * <p>All aggregation is delegated to {@link OTCloudStatisticsService} so that the Solr query
 * details live in one place and this controller stays a thin REST adapter.</p>
 */
@RestController
@RequestMapping("/api/" + RestModel.OTCLOUD_STATS)
public class OTCloudStatsController implements InitializingBean {

    @Autowired
    private DiscoverableEndpointsService discoverableEndpointsService;

    @Autowired
    private DSpaceObjectUtils dspaceObjectUtil;

    @Autowired
    private ConverterService converter;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ConfigurationService configurationService;

    @Autowired
    private ItemService itemService;

    @Autowired
    private OTCloudStatisticsService otCloudStatisticsService;

    @Autowired
    private MetadataUsageService metadataUsageService;

    @Override
    public void afterPropertiesSet() throws Exception {
        discoverableEndpointsService.register(this, List.of(
                Link.of("/api/" + RestModel.OTCLOUD_STATS + "/top-items", "top-items"),
                Link.of("/api/" + RestModel.OTCLOUD_STATS + "/metadata-usage", "metadata-usage"),
                Link.of("/api/" + RestModel.OTCLOUD_STATS + "/top-searches", "top-searches")));
    }

    /**
     * Aggregate views and downloads by the values of a metadata field, for example the most viewed
     * authors, departments or publication types.
     *
     * <p>The usage statistics core stores no item metadata, so this report is built by grouping the
     * most viewed items in scope by their metadata. The number of items aggregated is capped; when
     * the cap is reached every row carries {@code truncated: true} and the counts describe the most
     * viewed items rather than repository-wide totals.</p>
     *
     * @param uuid          the community, collection or site to report on
     * @param metadataField the field to group by, in {@code schema.element[.qualifier]} form
     * @param startDateStr  optional inclusive start of the reporting period
     * @param endDateStr    optional inclusive end of the reporting period
     * @param pageable      the requested page
     * @param request       the current request, used to obtain the DSpace context
     * @param response      the current response
     * @return a page of metadata values ordered by descending views
     */
    @GetMapping("/metadata-usage")
    public Page<MetadataUsageResource> getMetadataUsage(
            @RequestParam(name = "uuid") UUID uuid,
            @RequestParam(name = "field") String metadataField,
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            Pageable pageable, HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (!(dso instanceof Community || dso instanceof Collection || dso instanceof Site)) {
                throw new ResourceNotFoundException(
                        "No Community, Collection or Site found with uuid: " + uuid);
            }
            authorizeStatisticsAccess(context, dso);

            StatDateRange range = new StatDateRange(parseDate(startDateStr, "startDate"),
                                                    parseDate(endDateStr, "endDate"));
            DSpaceObject scope = dso instanceof Site ? null : dso;

            MetadataUsageTable table;
            try {
                table = metadataUsageService.aggregate(context, scope, metadataField, range,
                                                      (int) pageable.getOffset(), pageable.getPageSize());
            } catch (IllegalArgumentException e) {
                throw new DSpaceBadRequestException(e.getMessage());
            }

            List<MetadataUsageRest> rows = new ArrayList<>();
            for (MetadataUsageRow row : table.getRows()) {
                MetadataUsageRest rest = new MetadataUsageRest();
                rest.setId(metadataField + ":" + row.getValue());
                rest.setValue(row.getValue());
                rest.setViews(row.getViews());
                rest.setDownloads(row.getDownloads());
                rest.setItems(row.getItems());
                rest.setTruncated(table.isTruncated());
                rest.setItemsConsidered(table.getItemsConsidered());
                rows.add(rest);
            }

            return new PageImpl<>(rows, pageable, table.getTotalRows())
                    .map(row -> (MetadataUsageResource) converter.toResource(row));

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Return a page of the most viewed items within a community, collection or the whole site,
     * each with its view and download counts.
     *
     * @param uuid         the community, collection or site to report on
     * @param startDateStr optional inclusive start of the reporting period
     * @param endDateStr   optional inclusive end of the reporting period
     * @param pageable     the requested page
     * @param request      the current request, used to obtain the DSpace context
     * @param response     the current response
     * @return a page of item statistics ordered by descending view count
     */
    @GetMapping("/top-items")
    public Page<ItemStatsResource> getTopItems(
            @RequestParam(name = "uuid") UUID uuid,
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            Pageable pageable, HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (!(dso instanceof Community || dso instanceof Collection || dso instanceof Site)) {
                throw new ResourceNotFoundException(
                        "No Community, Collection or Site found with uuid: " + uuid);
            }

            authorizeStatisticsAccess(context, dso);

            StatDateRange range = new StatDateRange(parseDate(startDateStr, "startDate"),
                                                    parseDate(endDateStr, "endDate"));

            DSpaceObject scope = dso instanceof Site ? null : dso;
            StatBucketPage buckets = otCloudStatisticsService.topItems(scope, range,
                                                                      (int) pageable.getOffset(),
                                                                      pageable.getPageSize());

            List<ItemStatsRest> stats = toItemStats(context, buckets);
            return new PageImpl<>(stats, pageable, buckets.getTotalBuckets())
                    .map(stat -> (ItemStatsResource) converter.toResource(stat));

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Return a page of the most frequently run search queries within a community, collection or
     * the whole site, ordered by descending frequency.
     *
     * <p>Counts both searches that ended without a clicked result and ones that did, since both
     * represent a query someone actually ran.</p>
     *
     * @param uuid         the community, collection or site to report on
     * @param startDateStr optional inclusive start of the reporting period
     * @param endDateStr   optional inclusive end of the reporting period
     * @param pageable     the requested page
     * @param request      the current request, used to obtain the DSpace context
     * @param response     the current response
     * @return a page of search terms ordered by descending frequency
     */
    @GetMapping("/top-searches")
    public Page<SearchTermResource> getTopSearches(
            @RequestParam(name = "uuid") UUID uuid,
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            Pageable pageable, HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (!(dso instanceof Community || dso instanceof Collection || dso instanceof Site)) {
                throw new ResourceNotFoundException(
                        "No Community, Collection or Site found with uuid: " + uuid);
            }

            authorizeStatisticsAccess(context, dso);

            StatDateRange range = new StatDateRange(parseDate(startDateStr, "startDate"),
                                                    parseDate(endDateStr, "endDate"));

            DSpaceObject scope = dso instanceof Site ? null : dso;
            StatBucketPage buckets = otCloudStatisticsService.topSearches(scope, range,
                                                                          (int) pageable.getOffset(),
                                                                          pageable.getPageSize());

            List<SearchTermRest> terms = new ArrayList<>();
            int rank = (int) pageable.getOffset();
            for (StatBucket bucket : buckets.getBuckets()) {
                SearchTermRest rest = new SearchTermRest();
                rest.setId(uuid + ":" + (rank++));
                rest.setQuery(bucket.getValue());
                rest.setCount((int) bucket.getViews());
                terms.add(rest);
            }

            return new PageImpl<>(terms, pageable, buckets.getTotalBuckets())
                    .map(term -> (SearchTermResource) converter.toResource(term));

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Resolve each statistics bucket to the item it refers to, dropping buckets whose item no
     * longer exists or is not readable by the current user.
     *
     * @param context the DSpace context
     * @param buckets the statistics buckets to resolve
     * @return the resolved item statistics, in the order the buckets were returned
     * @throws SQLException if the item lookup fails
     */
    private List<ItemStatsRest> toItemStats(Context context, StatBucketPage buckets) throws SQLException {
        List<ItemStatsRest> stats = new ArrayList<>();
        for (StatBucket bucket : buckets.getBuckets()) {
            UUID itemUuid = toUuid(bucket.getValue());
            if (itemUuid == null) {
                continue;
            }
            Item item = itemService.find(context, itemUuid);
            if (item == null) {
                continue;
            }

            ItemStatsRest itemStats = new ItemStatsRest();
            itemStats.setId(itemUuid.toString());
            itemStats.setLabel(item.getName());
            itemStats.setViews((int) bucket.getViews());
            itemStats.setDownloads((int) bucket.getDownloads());
            stats.add(itemStats);
        }
        return stats;
    }

    /**
     * Apply the same visibility rules the stock usage reports use: administrators only when
     * {@code usage-statistics.authorization.admin.usage} is set, otherwise anyone who can read the
     * object.
     *
     * @param context the DSpace context
     * @param dso     the object being reported on
     * @throws SQLException if the authorization check fails
     */
    private void authorizeStatisticsAccess(Context context, DSpaceObject dso) throws SQLException {
        if (configurationService.getBooleanProperty("usage-statistics.authorization.admin.usage", false)) {
            if (!authorizeService.isAdmin(context)) {
                throw new AccessDeniedException("The statistics are only visible to administrators.");
            }
        } else if (!authorizeService.authorizeActionBoolean(context, dso, Constants.READ)) {
            throw new AccessDeniedException("The statistics are only visible to users with READ access.");
        }
    }

    private UUID toUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Parse a request date, accepting either a full ISO date-time or an ISO instant. Both are
     * interpreted as UTC, matching how DSpace stores usage event timestamps.
     *
     * @param dateStr   the raw request value, may be blank
     * @param paramName the parameter name, used in the error message
     * @return the parsed value, or null when nothing was supplied
     */
    private LocalDateTime parseDate(String dateStr, String paramName) {
        if (StringUtils.isBlank(dateStr)) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.ofInstant(Instant.parse(dateStr), ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new DSpaceBadRequestException(
                        "Invalid " + paramName + " value, expected an ISO date-time: " + dateStr);
            }
        }
    }
}
