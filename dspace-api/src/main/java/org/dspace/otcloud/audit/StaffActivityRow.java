/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.otcloud.audit;

import java.util.UUID;

/**
 * One staff member's recorded activity over a reporting period.
 *
 * <p>Every count here is a count of <em>distinct objects</em>, not of audit rows. The audit core
 * stores one row per changed metadata value, so a single edit that touches five fields produces
 * five rows; counting rows would badly overstate how much work was done.</p>
 */
public class StaffActivityRow {

    private final UUID epersonUuid;
    private long itemsCreated;
    private long itemsArchived;
    private long itemsEdited;
    private long itemsDeleted;
    private long bitstreamsAdded;
    private long totalEvents;

    /**
     * @param epersonUuid the staff member the counts belong to
     */
    public StaffActivityRow(UUID epersonUuid) {
        this.epersonUuid = epersonUuid;
    }

    public UUID getEpersonUuid() {
        return epersonUuid;
    }

    /**
     * @return distinct items this person created, including submissions not yet archived
     */
    public long getItemsCreated() {
        return itemsCreated;
    }

    public void setItemsCreated(long itemsCreated) {
        this.itemsCreated = itemsCreated;
    }

    /**
     * @return distinct items this person put into the archive, that is completed submissions
     */
    public long getItemsArchived() {
        return itemsArchived;
    }

    public void setItemsArchived(long itemsArchived) {
        this.itemsArchived = itemsArchived;
    }

    /**
     * @return distinct items whose metadata this person changed
     */
    public long getItemsEdited() {
        return itemsEdited;
    }

    public void setItemsEdited(long itemsEdited) {
        this.itemsEdited = itemsEdited;
    }

    /**
     * @return distinct items this person deleted
     */
    public long getItemsDeleted() {
        return itemsDeleted;
    }

    public void setItemsDeleted(long itemsDeleted) {
        this.itemsDeleted = itemsDeleted;
    }

    /**
     * @return distinct bitstreams this person added
     */
    public long getBitstreamsAdded() {
        return bitstreamsAdded;
    }

    public void setBitstreamsAdded(long bitstreamsAdded) {
        this.bitstreamsAdded = bitstreamsAdded;
    }

    /**
     * @return the raw number of audit rows attributed to this person, useful as an activity volume
     *         indicator alongside the distinct-object counts
     */
    public long getTotalEvents() {
        return totalEvents;
    }

    public void setTotalEvents(long totalEvents) {
        this.totalEvents = totalEvents;
    }
}
