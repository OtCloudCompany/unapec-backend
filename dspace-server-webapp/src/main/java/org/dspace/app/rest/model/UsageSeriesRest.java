/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.dspace.app.rest.RestResourceController;

/**
 * REST model for a usage time series over a single object, community, collection or the whole site.
 *
 * <p>This backs the small usage graphs shown alongside an object, giving views and downloads
 * bucketed by day, month or year in one payload so the client needs a single request per graph.</p>
 */
public class UsageSeriesRest extends BaseObjectRest<String> {
    public static final String NAME = "usage-series";
    public static final String CATEGORY = RestModel.OTCLOUD_STATS;

    private String id;
    private String label;
    private String gap;
    private List<UsageSeriesPointRest> points = new ArrayList<>();

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
     * @return the calendar interval each point covers, one of {@code day}, {@code month} or
     *         {@code year}
     */
    public String getGap() {
        return gap;
    }

    public void setGap(String gap) {
        this.gap = gap;
    }

    public List<UsageSeriesPointRest> getPoints() {
        return points;
    }

    public void setPoints(List<UsageSeriesPointRest> points) {
        this.points = points;
    }

    public void addPoint(UsageSeriesPointRest point) {
        this.points.add(point);
    }
}
