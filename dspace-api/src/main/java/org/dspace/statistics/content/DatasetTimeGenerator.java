/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.statistics.content;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Represents a date facet for filtering.
 *
 * @author kevinvandevelde at atmire.com
 * Date: 23-dec-2008
 * Time: 9:44:57
 */
public class DatasetTimeGenerator extends DatasetGenerator {

    private String type = "time";
    private String dateType;
    private String startDate;
    private String endDate;
    private LocalDateTime actualStartDate;
    private LocalDateTime actualEndDate;

    //TODO: process includetotal

    /**
     * Default constructor
     */
    public DatasetTimeGenerator() { }

    /**
     * Sets the date interval.
     * For example if you wish to see the data from today to six months ago give
     * the following parameters:
     * datatype = "month"
     * start = "-6"
     * end = "+1" // the +1 indicates this month also
     *
     * @param dateType type can be days, months, years
     * @param start    the start of the interval
     * @param end      the end of the interval
     */
    public void setDateInterval(String dateType, String start, String end) {
        this.startDate = start;
        this.endDate = end;
        this.dateType = dateType;

    }

    public void setDateInterval(String dateType, LocalDateTime start, LocalDateTime end)
        throws IllegalArgumentException {
        actualStartDate = start;
        actualEndDate = end;

        this.dateType = dateType;

        //Check if end comes before start
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date is before start date");
        }

        // TODO: ensure future dates are tested. Although we normally do not
        // have visits from the future.
        //Depending on our dateType check if we need to use days/months/years.
        ChronoUnit typeChronoUnit = ChronoUnit.DAYS;
        if ("year".equalsIgnoreCase(dateType)) {
            typeChronoUnit = ChronoUnit.YEARS;
        } else if ("month".equalsIgnoreCase(dateType)) {
            typeChronoUnit = ChronoUnit.MONTHS;
        } else if ("day".equalsIgnoreCase(dateType)) {
            typeChronoUnit = ChronoUnit.DAYS;
        } else if ("hour".equalsIgnoreCase(dateType)) {
            typeChronoUnit = ChronoUnit.HOURS;
        }

        long difStart = typeChronoUnit.between(start, LocalDateTime.now());
        long difEnd = typeChronoUnit.between(end, LocalDateTime.now());

        startDate = (difStart >= 0 ? "-" : "+") + Math.abs(difStart);
        // We need to ensure the end date is inclusive of the end month/day/etc.
        // If difEnd is 0 (this unit), we want to go until the start of NEXT unit (+1)
        // If difEnd is positive (past unit), we want to go until the start of the unit AFTER it.
        // For example, if end is last month (difEnd=1), we want until start of this month (NOW/MONTH-0MONTHS)
        long adjustedDifEnd = difEnd - 1;
        endDate = (adjustedDifEnd >= 0 ? "-" : "+") + Math.abs(adjustedDifEnd);
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getDateType() {
        return dateType.toUpperCase();
    }

    public LocalDateTime getActualStartDate() {
        return actualStartDate;
    }

    public void setActualStartDate(LocalDateTime actualStartDate) {
        this.actualStartDate = actualStartDate;
    }

    public LocalDateTime getActualEndDate() {
        return actualEndDate;
    }

    public void setActualEndDate(LocalDateTime actualEndDate) {
        this.actualEndDate = actualEndDate;
    }

    public void setDateType(String dateType) {
        this.dateType = dateType;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
