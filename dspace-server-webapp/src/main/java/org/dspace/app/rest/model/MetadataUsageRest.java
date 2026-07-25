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
 * REST model for one row of a usage-by-metadata table: a single metadata value with the traffic
 * attributed to the items carrying it.
 */
public class MetadataUsageRest extends BaseObjectRest<String> {
    public static final String NAME = "metadata-usage";
    public static final String CATEGORY = RestModel.OTCLOUD_STATS;

    private String id;
    private String value;
    private long views;
    private long downloads;
    private long items;
    private boolean truncated;
    private long itemsConsidered;

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

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }

    public long getDownloads() {
        return downloads;
    }

    public void setDownloads(long downloads) {
        this.downloads = downloads;
    }

    /**
     * @return how many distinct items carrying this value contributed to the counts
     */
    public long getItems() {
        return items;
    }

    public void setItems(long items) {
        this.items = items;
    }

    /**
     * @return true when the aggregation hit its item ceiling, meaning these counts cover the most
     *         viewed items in scope rather than every item
     */
    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    /**
     * @return how many items were aggregated to produce this table
     */
    public long getItemsConsidered() {
        return itemsConsidered;
    }

    public void setItemsConsidered(long itemsConsidered) {
        this.itemsConsidered = itemsConsidered;
    }
}
