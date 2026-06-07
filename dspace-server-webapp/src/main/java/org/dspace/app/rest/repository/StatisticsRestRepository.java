/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.repository;

import java.io.IOException;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.app.rest.Parameter;
import org.dspace.app.rest.SearchRestMethod;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.exception.RepositoryMethodNotImplementedException;
import org.dspace.app.rest.model.StatisticsSupportRest;
import org.dspace.app.rest.model.UsageReportRest;
import org.dspace.app.rest.utils.DSpaceObjectUtils;
import org.dspace.app.rest.utils.UsageReportUtils;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.rest.webmvc.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

@Component(StatisticsSupportRest.CATEGORY + "." + UsageReportRest.PLURAL_NAME)
public class StatisticsRestRepository extends DSpaceRestRepository<UsageReportRest, String> {

    @Autowired
    private DSpaceObjectUtils dspaceObjectUtil;

    @Autowired
    private UsageReportUtils usageReportUtils;

    @Autowired
    private HttpServletRequest request;

    public StatisticsSupportRest getStatisticsSupport() {
        return new StatisticsSupportRest();
    }

    @Override
    @PreAuthorize("hasPermission(#uuidObjectReportId, 'usagereport', 'READ')")
    public UsageReportRest findOne(Context context, String uuidObjectReportId) {
        UUID uuidObject = UUID.fromString(StringUtils.substringBefore(uuidObjectReportId, "_"));
        String reportId = StringUtils.substringAfter(uuidObjectReportId, "_");

        UsageReportRest usageReportRest = null;
        try {
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuidObject);
            if (dso == null) {
                throw new ResourceNotFoundException("No DSO found with uuid: " + uuidObject);
            }
            LocalDateTime startDate = parseDate(request.getParameter("startDate"), false);
            LocalDateTime endDate = parseDate(request.getParameter("endDate"), true);
            if ((startDate != null && endDate == null) || (startDate == null && endDate != null)) {
                throw new DSpaceBadRequestException("Both startDate and endDate parameters must be provided together");
            }
            usageReportRest = usageReportUtils.createUsageReport(context, dso, reportId, startDate, endDate);

        } catch (ParseException | SolrServerException | IOException | SQLException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        return converter.toRest(usageReportRest, utils.obtainProjection());
    }

    @PreAuthorize("hasPermission(#uri, 'usagereportsearch', 'READ')")
    @SearchRestMethod(name = "object")
    public Page<UsageReportRest> findByObject(@Parameter(value = "uri", required = true) String uri,
                                              Pageable pageable) {
        UUID uuid = UUID.fromString(StringUtils.substringAfterLast(uri, "/"));
        List<UsageReportRest> usageReportsOfItem = null;
        try {
            Context context = obtainContext();
            DSpaceObject dso = dspaceObjectUtil.findDSpaceObject(context, uuid);
            if (dso == null) {
                throw new ResourceNotFoundException("No DSO found with uuid: " + uuid);
            }
            usageReportsOfItem = usageReportUtils.getUsageReportsOfDSO(context, dso);
        } catch (SQLException | ParseException | SolrServerException | IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        return converter.toRestPage(usageReportsOfItem, pageable, usageReportsOfItem.size(), utils.obtainProjection());
    }

    private LocalDateTime parseDate(String dateString) {
        return parseDate(dateString, false);
    }

    private LocalDateTime parseDate(String dateString, boolean endOfDay) {
        if (dateString == null) {
            return null;
        }

        try {
            return LocalDateTime.parse(dateString);
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(dateString).toLocalDateTime();
            } catch (DateTimeParseException e2) {
                try {
                    LocalDate localDate = LocalDate.parse(dateString);
                    return endOfDay ? localDate.atTime(LocalTime.MAX) : localDate.atStartOfDay();
                } catch (DateTimeParseException e3) {
                    throw new DSpaceBadRequestException("Invalid date format for parameter: '" + dateString + "'. " +
                        "Use ISO-8601 date or datetime format like 2024-01-01 or 2024-01-01T12:00:00");
                }
            }
        }
    }

    @Override
    public Page<UsageReportRest> findAll(Context context, Pageable pageable) {
        throw new RepositoryMethodNotImplementedException("No implementation found; Method not allowed!", "findAll");
    }

    @Override
    public Class<UsageReportRest> getDomainClass() {
        return UsageReportRest.class;
    }
}
