/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.audit;

import java.util.List;

/**
 * One page of staff activity rows plus the total number of staff members who were active in the
 * reporting period.
 */
public class StaffActivityPage {

    private final List<StaffActivityRow> rows;
    private final long totalStaff;
    private final long totalItemsCreated;

    /**
     * @param rows              the rows on the requested page
     * @param totalStaff        the total number of staff members with any recorded activity in the period
     * @param totalItemsCreated the total number of distinct items created in the period, across every staff
     *                          member - not just the ones on this page
     */
    public StaffActivityPage(List<StaffActivityRow> rows, long totalStaff, long totalItemsCreated) {
        this.rows = rows;
        this.totalStaff = totalStaff;
        this.totalItemsCreated = totalItemsCreated;
    }

    public List<StaffActivityRow> getRows() {
        return rows;
    }

    public long getTotalStaff() {
        return totalStaff;
    }

    public long getTotalItemsCreated() {
        return totalItemsCreated;
    }
}
