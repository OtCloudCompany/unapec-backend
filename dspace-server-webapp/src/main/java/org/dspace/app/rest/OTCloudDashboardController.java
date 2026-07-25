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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.RestModel;
import org.dspace.app.rest.model.UsageDashboardRest;
import org.dspace.app.rest.model.UsageSeriesPointRest;
import org.dspace.app.rest.model.UsageSeriesRest;
import org.dspace.app.rest.model.hateoas.UsageDashboardResource;
import org.dspace.app.rest.model.hateoas.UsageSeriesResource;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.DSpaceObjectUtils;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.Site;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.otcloud.statistics.OTCloudStatisticsService;
import org.dspace.otcloud.statistics.StatBucket;
import org.dspace.otcloud.statistics.StatBucketPage;
import org.dspace.otcloud.statistics.StatDateRange;
import org.dspace.services.ConfigurationService;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Repository-wide and per-container usage summaries, and the compact usage time series that back
 * the small graphs shown next to an object.
 *
 * <p>The stock {@code usagereports} endpoint answers one question per request and only reports
 * total visits for the whole site. These endpoints report at site, community, collection and item
 * level over an arbitrary period, and bundle the figures a dashboard or graph needs into a single
 * response.</p>
 */
@RestController
@RequestMapping("/api/" + RestModel.OTCLOUD_STATS)
public class OTCloudDashboardController implements InitializingBean {

    /**
     * Calendar intervals a time series may be bucketed by, mapped to Solr date-math gaps.
     */
    private static final Map<String, String> GAPS = Map.of(
            "day", "+1DAY",
            "month", "+1MONTH",
            "year", "+1YEAR");

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
    private OTCloudStatisticsService otCloudStatisticsService;

    @Override
    public void afterPropertiesSet() throws Exception {
        discoverableEndpointsService.register(this, List.of(
                Link.of("/api/" + RestModel.OTCLOUD_STATS + "/dashboard", "dashboard"),
                Link.of("/api/" + RestModel.OTCLOUD_STATS + "/series", "series")));
    }

    /**
     * Summarise usage for the whole site, a community or a collection over a period.
     *
     * @param uuid         the site, community or collection to report on
     * @param startDateStr optional inclusive start of the reporting period
     * @param endDateStr   optional inclusive end of the reporting period
     * @param request      the current request, used to obtain the DSpace context
     * @param response     the current response
     * @return the usage summary
     */
    @GetMapping("/dashboard")
    public UsageDashboardResource getDashboard(
            @RequestParam(name = "uuid") UUID uuid,
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (!(dso instanceof Site || dso instanceof Community || dso instanceof Collection)) {
                throw new ResourceNotFoundException(
                        "No Site, Community or Collection found with uuid: " + uuid);
            }
            authorizeStatisticsAccess(context, dso);

            StatDateRange range = parseRange(startDateStr, endDateStr);
            DSpaceObject scope = dso instanceof Site ? null : dso;
            String scopeFilter = otCloudStatisticsService.scopeFilter(scope);

            UsageDashboardRest dashboard = new UsageDashboardRest();
            dashboard.setId(uuid.toString());
            dashboard.setLabel(dso.getName());
            dashboard.setScopeType(scopeTypeOf(dso));

            dashboard.setViews(otCloudStatisticsService.total(scopeFilter, Constants.ITEM, range));
            dashboard.setDownloads(otCloudStatisticsService.total(scopeFilter, Constants.BITSTREAM, range));
            dashboard.setItemsViewed(
                    otCloudStatisticsService.countDistinct(scopeFilter, "id", Constants.ITEM, range));

            int topCountries = configurationService.getIntProperty("usage-statistics.topCountriesLimit", 100);
            int topCities = configurationService.getIntProperty("usage-statistics.topCitiesLimit", 100);

            copyBuckets(otCloudStatisticsService.facetTerms(scopeFilter, "countryCode", -1, range, 0, topCountries),
                        dashboard.getTopCountries());
            copyBuckets(otCloudStatisticsService.facetTerms(scopeFilter, "city", -1, range, 0, topCities),
                        dashboard.getTopCities());

            return (UsageDashboardResource) converter.toResource(dashboard);

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Return a usage time series of views and downloads for any object, community, collection or the
     * whole site.
     *
     * <p>For an item, views are visits to the item itself and downloads are of its own bitstreams.
     * For a container, views are visits to the items it contains and downloads are of their
     * bitstreams; because the usage events carry every ancestor community, a community series
     * transparently includes its sub-communities.</p>
     *
     * @param uuid         the object to report on
     * @param startDateStr inclusive start of the reporting period, required
     * @param endDateStr   inclusive end of the reporting period, required
     * @param gapName      the interval to bucket by: day, month or year
     * @param request      the current request, used to obtain the DSpace context
     * @param response     the current response
     * @return the usage series
     */
    @GetMapping("/series")
    public UsageSeriesResource getSeries(
            @RequestParam(name = "uuid") UUID uuid,
            @RequestParam(name = "startDate") String startDateStr,
            @RequestParam(name = "endDate") String endDateStr,
            @RequestParam(name = "gap", defaultValue = "month") String gapName,
            HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            String gap = GAPS.get(gapName.toLowerCase(Locale.ROOT));
            if (gap == null) {
                throw new DSpaceBadRequestException(
                        "Invalid gap value, expected one of day, month, year: " + gapName);
            }

            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (dso == null) {
                throw new ResourceNotFoundException(
                        "No DSpaceObject found with uuid: " + uuid);
            }
            authorizeStatisticsAccess(context, dso);

            LocalDateTime start = parseDate(startDateStr, "startDate");
            LocalDateTime end = parseDate(endDateStr, "endDate");
            if (start == null || end == null) {
                throw new DSpaceBadRequestException("Both startDate and endDate are required for a series");
            }
            StatDateRange range = new StatDateRange(start, end);

            // An item's own views live under its id; everything else aggregates its descendants.
            // Either way the events counted are of type ITEM.
            DSpaceObject scope = dso instanceof Site ? null : dso;
            String viewFilter = dso instanceof Item
                    ? otCloudStatisticsService.selfFilter(dso)
                    : otCloudStatisticsService.scopeFilter(scope);
            String downloadFilter = otCloudStatisticsService.scopeFilter(scope);

            List<StatBucket> viewSeries =
                    otCloudStatisticsService.timeSeries(viewFilter, Constants.ITEM, range, gap);
            List<StatBucket> downloadSeries =
                    otCloudStatisticsService.timeSeries(downloadFilter, Constants.BITSTREAM, range, gap);

            Map<String, Long> downloadsByDate = new HashMap<>();
            for (StatBucket bucket : downloadSeries) {
                downloadsByDate.put(bucket.getValue(), bucket.getViews());
            }

            UsageSeriesRest series = new UsageSeriesRest();
            series.setId(uuid.toString());
            series.setLabel(dso.getName());
            series.setGap(gapName.toLowerCase(Locale.ROOT));
            for (StatBucket bucket : viewSeries) {
                UsageSeriesPointRest point = new UsageSeriesPointRest();
                point.setDate(bucket.getValue());
                point.setViews(bucket.getViews());
                point.setDownloads(downloadsByDate.getOrDefault(bucket.getValue(), 0L));
                series.addPoint(point);
            }

            return (UsageSeriesResource) converter.toResource(series);

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    private void copyBuckets(StatBucketPage page, List<Map<String, Object>> target) {
        for (StatBucket bucket : page.getBuckets()) {
            UsageDashboardRest.addPoint(target, bucket.getValue(), bucket.getViews());
        }
    }

    private String scopeTypeOf(DSpaceObject dso) {
        if (dso instanceof Site) {
            return "site";
        }
        if (dso instanceof Community) {
            return "community";
        }
        return "collection";
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

    private StatDateRange parseRange(String startDateStr, String endDateStr) {
        return new StatDateRange(parseDate(startDateStr, "startDate"), parseDate(endDateStr, "endDate"));
    }

    /**
     * Parse a request date, accepting either a full ISO date-time or an ISO instant, both read as
     * UTC to match how DSpace stores usage event timestamps.
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
