/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dspace.app.rest.RestResourceController;

/**
 * REST model for one staff member's recorded activity over a reporting period.
 *
 * <p>Each count is a number of distinct objects affected, not a number of audit records, so an edit
 * that changes several metadata fields on one item counts once.</p>
 */
public class StaffActivityRest extends BaseObjectRest<String> {
    public static final String NAME = "staff-activity";
    public static final String CATEGORY = RestModel.OTCLOUD_STATS;

    private String id;
    private String name;
    private String email;
    private long itemsCreated;
    private long itemsArchived;
    private long itemsEdited;
    private long itemsDeleted;
    private long bitstreamsAdded;
    private long totalEvents;

    @Override
    public String getCategory() {
        return CATEGORY;
    }

    @Override
    public Class getController() {
        return RestResourceController.class;
    }

    @Override
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public String getType() {
        return NAME;
    }

    @Override
    public String getTypePlural() {
        return NAME;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public long getItemsCreated() {
        return itemsCreated;
    }

    public void setItemsCreated(long itemsCreated) {
        this.itemsCreated = itemsCreated;
    }

    public long getItemsArchived() {
        return itemsArchived;
    }

    public void setItemsArchived(long itemsArchived) {
        this.itemsArchived = itemsArchived;
    }

    public long getItemsEdited() {
        return itemsEdited;
    }

    public void setItemsEdited(long itemsEdited) {
        this.itemsEdited = itemsEdited;
    }

    public long getItemsDeleted() {
        return itemsDeleted;
    }

    public void setItemsDeleted(long itemsDeleted) {
        this.itemsDeleted = itemsDeleted;
    }

    public long getBitstreamsAdded() {
        return bitstreamsAdded;
    }

    public void setBitstreamsAdded(long bitstreamsAdded) {
        this.bitstreamsAdded = bitstreamsAdded;
    }

    public long getTotalEvents() {
        return totalEvents;
    }

    public void setTotalEvents(long totalEvents) {
        this.totalEvents = totalEvents;
    }
}
