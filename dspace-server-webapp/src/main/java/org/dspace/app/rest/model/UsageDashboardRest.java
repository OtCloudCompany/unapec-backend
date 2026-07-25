/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dspace.app.rest.RestResourceController;

/**
 * REST model for a usage summary of the whole site, a community or a collection.
 *
 * <p>Gathers in one response the figures a repository dashboard shows together - totals, how many
 * distinct items were actually looked at, and where the traffic came from - so that rendering a
 * dashboard does not require a request per figure.</p>
 */
public class UsageDashboardRest extends BaseObjectRest<String> {
    public static final String NAME = "usage-dashboard";
    public static final String CATEGORY = RestModel.OTCLOUD_STATS;

    private String id;
    private String label;
    private String scopeType;
    private long views;
    private long downloads;
    private long itemsViewed;
    private List<Map<String, Object>> topCountries = new ArrayList<>();
    private List<Map<String, Object>> topCities = new ArrayList<>();

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

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    /**
     * @return what the figures cover: {@code site}, {@code community} or {@code collection}
     */
    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    /**
     * @return item page views in scope
     */
    public long getViews() {
        return views;
    }

    public void setViews(long views) {
        this.views = views;
    }

    /**
     * @return bitstream downloads in scope
     */
    public long getDownloads() {
        return downloads;
    }

    public void setDownloads(long downloads) {
        this.downloads = downloads;
    }

    /**
     * @return how many distinct items were viewed at least once, which tells a very different story
     *         from the raw view total when a handful of items dominate the traffic
     */
    public long getItemsViewed() {
        return itemsViewed;
    }

    public void setItemsViewed(long itemsViewed) {
        this.itemsViewed = itemsViewed;
    }

    public List<Map<String, Object>> getTopCountries() {
        return topCountries;
    }

    public void setTopCountries(List<Map<String, Object>> topCountries) {
        this.topCountries = topCountries;
    }

    public List<Map<String, Object>> getTopCities() {
        return topCities;
    }

    public void setTopCities(List<Map<String, Object>> topCities) {
        this.topCities = topCities;
    }

    /**
     * Add a labelled count to one of the top lists.
     *
     * @param target the list to add to
     * @param label  the country code or city name
     * @param views  the number of views attributed to it
     */
    public static void addPoint(List<Map<String, Object>> target, String label, long views) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("label", label);
        point.put("views", views);
        target.add(point);
    }
}
