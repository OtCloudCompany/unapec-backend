/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.statistics;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * An inclusive, UTC-based date range used to scope usage statistics queries.
 *
 * <p>DSpace stores the usage event timestamp in the Solr {@code time} field as a UTC date, so the
 * bounds held here are always interpreted as UTC. A range where both bounds are {@code null}
 * represents "all time" and produces no Solr filter at all.</p>
 */
public class StatDateRange {

    private final LocalDateTime start;
    private final LocalDateTime end;

    /**
     * Create a range. Either bound may be null, in which case that side is unbounded.
     *
     * @param start inclusive lower bound, or null for unbounded
     * @param end   inclusive upper bound, or null for unbounded
     */
    public StatDateRange(LocalDateTime start, LocalDateTime end) {
        this.start = start;
        this.end = end;
    }

    /**
     * @return a range covering all time, which applies no time filter
     */
    public static StatDateRange allTime() {
        return new StatDateRange(null, null);
    }

    public LocalDateTime getStart() {
        return start;
    }

    public LocalDateTime getEnd() {
        return end;
    }

    /**
     * @return true if neither bound is set, meaning no time filter should be applied
     */
    public boolean isUnbounded() {
        return start == null && end == null;
    }

    /**
     * Render this range as a Solr range filter on the usage event {@code time} field. Unbounded
     * sides are rendered as the Solr wildcard {@code *}.
     *
     * @return a Solr filter query such as {@code time:[2024-01-01T00:00:00Z TO *]}, or null when
     *         the range is unbounded
     */
    public String toSolrFilterQuery() {
        return toSolrFilterQuery("time");
    }

    /**
     * Render this range as a Solr range filter on an arbitrary date field. The usage statistics core
     * names its event timestamp {@code time} while the audit core names its own {@code timeStamp},
     * so the field has to be supplied by the caller.
     *
     * @param field the Solr date field to filter on
     * @return a Solr filter query, or null when the range is unbounded
     */
    public String toSolrFilterQuery(String field) {
        if (isUnbounded()) {
            return null;
        }
        return field + ":[" + startBound() + " TO " + endBound() + "]";
    }

    /**
     * @return the lower bound as an ISO instant, or the Solr wildcard when unbounded
     */
    public String startBound() {
        return bound(start);
    }

    /**
     * @return the upper bound as an ISO instant, or the Solr wildcard when unbounded
     */
    public String endBound() {
        return bound(end);
    }

    private String bound(LocalDateTime value) {
        if (value == null) {
            return "*";
        }
        return DateTimeFormatter.ISO_INSTANT.format(value.toInstant(ZoneOffset.UTC));
    }
}
