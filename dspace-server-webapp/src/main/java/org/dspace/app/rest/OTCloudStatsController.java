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
import java.text.ParseException;
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
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.FacetField;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.model.ItemStatsRest;
import org.dspace.app.rest.model.RestModel;
import org.dspace.app.rest.model.hateoas.ItemStatsResource;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.app.rest.utils.DSpaceObjectUtils;
import org.dspace.app.rest.utils.Utils;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.content.Collection;
import org.dspace.content.Community;
import org.dspace.content.DSpaceObject;
import org.dspace.content.Item;
import org.dspace.content.factory.ContentServiceFactory;
import org.dspace.content.service.ItemService;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.services.ConfigurationService;
import org.dspace.statistics.SolrLoggerServiceImpl;
import org.dspace.statistics.factory.StatisticsServiceFactory;
import org.dspace.statistics.service.SolrLoggerService;
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
 * Custom controller for OTCloud statistics.
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
    private Utils utils;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private ConfigurationService configurationService;

    private final SolrLoggerService solrLoggerService = StatisticsServiceFactory.getInstance().getSolrLoggerService();
    private final ItemService itemService = ContentServiceFactory.getInstance().getItemService();

    @Override
    public void afterPropertiesSet() throws Exception {
        discoverableEndpointsService.register(this,
                List.of(Link.of("/api/" + RestModel.OTCLOUD_STATS + "/top-items", "top-items")));
    }

    @GetMapping("/top-items")
    public Page<ItemStatsResource> getTopItems(
            @RequestParam(name = "uuid", required = true) UUID uuid,
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            Pageable pageable, HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (dso == null || !(dso instanceof Community || dso instanceof Collection)) {
                throw new ResourceNotFoundException("No Community or Collection found with uuid: " + uuid);
            }

            if (configurationService.getBooleanProperty("usage-statistics.authorization.admin.usage", false)) {
                if (!authorizeService.isAdmin(context)) {
                    throw new AccessDeniedException("The statistics are only visible to administrators.");
                }
            } else if (!authorizeService.authorizeActionBoolean(context, dso, Constants.READ)) {
                throw new AccessDeniedException("The statistics are only visible to users with READ access.");
            }

            LocalDateTime startDate = parseDate(startDateStr);
            LocalDateTime endDate = parseDate(endDateStr);

            List<ItemStatsRest> stats = fetchTopItemsStats(context, dso, startDate, endDate, pageable);

            // For simplicity, we use stats.size() as total, in a real scenario we might need a separate count query
            return new PageImpl<>(stats, pageable, stats.size()).map(s -> (ItemStatsResource) converter.toResource(s));

        } catch (SQLException | SolrServerException | IOException | ParseException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    private List<ItemStatsRest> fetchTopItemsStats(Context context, DSpaceObject container,
                                                   LocalDateTime startDate, LocalDateTime endDate,
                                                   Pageable pageable)
            throws SolrServerException, IOException, SQLException {

        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("*:*");
        solrQuery.setRows(0);
        solrQuery.setFacet(true);
        solrQuery.setFacetLimit(pageable.getPageSize());
        solrQuery.setFacetMinCount(1);
        solrQuery.addFacetField("id");

        String filterQuery = "";
        if (container instanceof Community) {
            filterQuery = "owningComm:" + container.getID();
        } else {
            filterQuery = "owningColl:" + container.getID();
        }
        filterQuery += " AND type:" + Constants.ITEM;

        if (startDate != null && endDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
            String start = formatter.format(startDate.toInstant(ZoneOffset.UTC));
            String end = formatter.format(endDate.toInstant(ZoneOffset.UTC));
            filterQuery += " AND time:[" + start + " TO " + end + "]";
        }

        solrQuery.addFilterQuery(filterQuery);

        // We want top items, usually based on views
        solrQuery.addFilterQuery("statistics_type:" + SolrLoggerServiceImpl.StatisticsType.VIEW.text());

        QueryResponse response = solrLoggerService.query(solrQuery.getQuery(), solrQuery.getFilterQueries()[0],
                "id", 0, pageable.getPageSize(), null, null, null, null, null, false, 1, true);

        List<ItemStatsRest> results = new ArrayList<>();
        FacetField idFacet = response.getFacetField("id");
        if (idFacet != null) {
            for (FacetField.Count count : idFacet.getValues()) {
                ItemStatsRest itemStats = new ItemStatsRest();
                UUID itemUuid = UUID.fromString(count.getName());
                Item item = itemService.find(context, itemUuid);
                if (item != null) {
                    itemStats.setId(itemUuid.toString());
                    itemStats.setLabel(item.getName());
                    itemStats.setViews((int) count.getCount());

                    // Fetch downloads for this item specifically
                    itemStats.setDownloads(fetchDownloadsForItem(itemUuid, startDate, endDate));
                    results.add(itemStats);
                }
            }
        }

        return results;
    }

    private int fetchDownloadsForItem(UUID itemUuid, LocalDateTime startDate, LocalDateTime endDate)
            throws SolrServerException, IOException {
        SolrQuery solrQuery = new SolrQuery();
        solrQuery.setQuery("owningItem:" + itemUuid + " AND type:" + Constants.BITSTREAM +
                " AND statistics_type:" + SolrLoggerServiceImpl.StatisticsType.VIEW.text());

        if (startDate != null && endDate != null) {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;
            String start = formatter.format(startDate.toInstant(ZoneOffset.UTC));
            String end = formatter.format(endDate.toInstant(ZoneOffset.UTC));
            solrQuery.addFilterQuery("time:[" + start + " TO " + end + "]");
        }

        QueryResponse response = solrLoggerService.query(solrQuery.getQuery(),
                solrQuery.getFilterQueries() != null && solrQuery.getFilterQueries().length > 0 ?
                        solrQuery.getFilterQueries()[0] : null,
                null, 0, 0, null, null, null, null, null, false, 1, true);

        return (int) response.getResults().getNumFound();
    }

    private LocalDateTime parseDate(String dateStr) throws ParseException {
        if (StringUtils.isBlank(dateStr)) {
            return null;
        }
        try {
            return LocalDateTime.parse(dateStr, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e) {
            // Try ISO_INSTANT (standard for Solr/DSpace API)
            try {
                return LocalDateTime.ofInstant(java.time.Instant.parse(dateStr), ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new ParseException("Invalid date format: " + dateStr, 0);
            }
        }
    }
}
