/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.apache.solr.client.solrj.SolrServerException;
import org.dspace.app.rest.converter.ConverterService;
import org.dspace.app.rest.exception.DSpaceBadRequestException;
import org.dspace.app.rest.model.RestModel;
import org.dspace.app.rest.model.StaffActivityRest;
import org.dspace.app.rest.model.hateoas.StaffActivityResource;
import org.dspace.app.rest.utils.ContextUtil;
import org.dspace.authorize.service.AuthorizeService;
import org.dspace.core.Context;
import org.dspace.eperson.EPerson;
import org.dspace.eperson.service.EPersonService;
import org.dspace.otcloud.audit.StaffActivityPage;
import org.dspace.otcloud.audit.StaffActivityRow;
import org.dspace.otcloud.audit.StaffActivityService;
import org.dspace.otcloud.statistics.StatDateRange;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.hateoas.Link;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative reports over the audit trail.
 *
 * <p>The stock DSpace audit endpoints can only list events - all of them, or the ones touching a
 * single object. This controller answers the question an administrator actually has: how much did
 * each staff member create, archive, edit or delete over a given period.</p>
 */
@RestController
@RequestMapping("/api/" + RestModel.OTCLOUD_STATS)
public class OTCloudAuditReportController implements InitializingBean {

    /**
     * Cap on the number of rows a CSV export will produce, so that an export cannot be used to pull
     * the whole audit core into memory.
     */
    private static final int CSV_ROW_LIMIT = 10000;

    @Autowired
    private DiscoverableEndpointsService discoverableEndpointsService;

    @Autowired
    private ConverterService converter;

    @Autowired
    private AuthorizeService authorizeService;

    @Autowired
    private EPersonService ePersonService;

    @Autowired
    private StaffActivityService staffActivityService;

    @Override
    public void afterPropertiesSet() throws Exception {
        discoverableEndpointsService.register(this,
                List.of(Link.of("/api/" + RestModel.OTCLOUD_STATS + "/staff-activity", "staff-activity")));
    }

    /**
     * Report how many items each staff member created, archived, edited or deleted in a period.
     *
     * @param startDateStr optional inclusive start of the reporting period
     * @param endDateStr   optional inclusive end of the reporting period
     * @param pageable     the requested page
     * @param request      the current request, used to obtain the DSpace context
     * @param response     the current response
     * @return a page of staff activity rows, most active first
     */
    @GetMapping("/staff-activity")
    public Page<StaffActivityResource> getStaffActivity(
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            Pageable pageable, HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            requireAdmin(context);

            StatDateRange range = parseRange(startDateStr, endDateStr);
            StaffActivityPage activity = staffActivityService.staffActivity(range,
                                                                           (int) pageable.getOffset(),
                                                                           pageable.getPageSize());

            List<StaffActivityRest> rows = toRest(context, activity.getRows());
            return new PageImpl<>(rows, pageable, activity.getTotalStaff())
                    .map(row -> (StaffActivityResource) converter.toResource(row));

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    /**
     * The same report as {@link #getStaffActivity} but rendered as CSV for offline use.
     *
     * @param startDateStr optional inclusive start of the reporting period
     * @param endDateStr   optional inclusive end of the reporting period
     * @param request      the current request, used to obtain the DSpace context
     * @param response     the response the CSV is written to
     */
    @GetMapping("/staff-activity/csv")
    public void getStaffActivityCsv(
            @RequestParam(name = "startDate", required = false) String startDateStr,
            @RequestParam(name = "endDate", required = false) String endDateStr,
            HttpServletRequest request, HttpServletResponse response) {

        Context context = ContextUtil.obtainContext(request);
        try {
            requireAdmin(context);

            StatDateRange range = parseRange(startDateStr, endDateStr);
            StaffActivityPage activity = staffActivityService.staffActivity(range, 0, CSV_ROW_LIMIT);
            List<StaffActivityRest> rows = toRest(context, activity.getRows());

            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader("Content-Disposition", "attachment; filename=\"staff-activity.csv\"");

            PrintWriter writer = response.getWriter();
            writer.println("Name,Email,Items created,Items archived,Items edited,Items deleted,"
                           + "Bitstreams added,Audit records");
            for (StaffActivityRest row : rows) {
                writer.println(String.join(",",
                        csv(row.getName()),
                        csv(row.getEmail()),
                        String.valueOf(row.getItemsCreated()),
                        String.valueOf(row.getItemsArchived()),
                        String.valueOf(row.getItemsEdited()),
                        String.valueOf(row.getItemsDeleted()),
                        String.valueOf(row.getBitstreamsAdded()),
                        String.valueOf(row.getTotalEvents())));
            }
            writer.flush();

        } catch (SQLException | SolrServerException | IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        }
    }

    /**
     * Resolve each row's staff member so the report can show a name rather than a bare UUID.
     *
     * @param context the DSpace context
     * @param rows    the rows to resolve
     * @return the REST representations, in the order given
     * @throws SQLException if an EPerson lookup fails
     */
    private List<StaffActivityRest> toRest(Context context, List<StaffActivityRow> rows) throws SQLException {
        List<StaffActivityRest> result = new ArrayList<>();
        for (StaffActivityRow row : rows) {
            StaffActivityRest rest = new StaffActivityRest();
            rest.setId(row.getEpersonUuid().toString());

            EPerson eperson = ePersonService.find(context, row.getEpersonUuid());
            if (eperson != null) {
                rest.setName(eperson.getFullName());
                rest.setEmail(eperson.getEmail());
            }

            rest.setItemsCreated(row.getItemsCreated());
            rest.setItemsArchived(row.getItemsArchived());
            rest.setItemsEdited(row.getItemsEdited());
            rest.setItemsDeleted(row.getItemsDeleted());
            rest.setBitstreamsAdded(row.getBitstreamsAdded());
            rest.setTotalEvents(row.getTotalEvents());
            result.add(rest);
        }
        return result;
    }

    /**
     * The audit trail records who changed what, so it is readable by administrators only.
     *
     * @param context the DSpace context
     * @throws SQLException if the authorization check fails
     */
    private void requireAdmin(Context context) throws SQLException {
        if (!authorizeService.isAdmin(context)) {
            throw new AccessDeniedException("Audit reports are only available to administrators.");
        }
    }

    private StatDateRange parseRange(String startDateStr, String endDateStr) {
        return new StatDateRange(parseDate(startDateStr, "startDate"), parseDate(endDateStr, "endDate"));
    }

    /**
     * Parse a request date, accepting either a full ISO date-time or an ISO instant, both read as
     * UTC to match how audit timestamps are stored.
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

    /**
     * Quote a value for CSV output, escaping any embedded quotes.
     *
     * @param value the value to quote, may be null
     * @return the quoted value
     */
    private String csv(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
