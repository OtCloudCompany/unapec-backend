/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.hateoas;

import org.dspace.app.rest.model.MetadataUsageRest;
import org.dspace.app.rest.model.hateoas.annotations.RelNameDSpaceResource;
import org.dspace.app.rest.utils.Utils;

/**
 * Usage-by-metadata row resource.
 */
@RelNameDSpaceResource(MetadataUsageRest.NAME)
public class MetadataUsageResource extends DSpaceResource<MetadataUsageRest> {
    public MetadataUsageResource(MetadataUsageRest data, Utils utils) {
        super(data, utils);
    }
}
