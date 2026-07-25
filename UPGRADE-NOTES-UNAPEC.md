# UNAPEC customisations: upgrade notes

This fork of DSpace 9.2 carries two bodies of local work:

1. **Extended usage statistics** — reports the stock `usagereports` endpoint does not provide
   (repository dashboards, usage time series, top items per container, usage grouped by metadata
   field), written from scratch.
2. **The audit trail**, backported from DSpace 10.

This file exists so that a future DSpace upgrade is a mechanical exercise rather than an
archaeological one. It lists every upstream file that carries a local edit, and everything that was
deliberately left out.

## Design rule

**New behaviour lives in local packages; upstream files are edited only where there is no
alternative.** The extended statistics are entirely contained in `org.dspace.otcloud.*` plus new
REST controllers, and their Spring beans live in their own configuration files. The audit backport
could not follow that rule, because auditing works by observing DSpace's event system and that meant
changing how events carry their details.

## Upstream files carrying local edits

### Audit backport prerequisite — structured event details (upstream PR #10684)

`Event` used to carry a single free-text `detail` string. It now carries a list of typed
`EventDetail` objects, so a consumer can tell a handle from an action name from a metadata summary.
`DSpaceObject` gained a parallel structured channel for metadata changes alongside the old string
cache, which is kept and deprecated.

| File | Nature of the edit |
| --- | --- |
| `dspace-api/.../event/Event.java` | Replaced wholesale with the DSpace 10 version |
| `dspace-api/.../content/DSpaceObject.java` | Added `metadataEventDetails` set and its accessors; `addMetadata` now records a `MetadataEvent` |
| `dspace-api/.../content/DSpaceObjectServiceImpl.java` | Emit `MetadataEvent` on metadata add and both remove paths |
| `dspace-api/.../content/ItemServiceImpl.java` | `DetailType` on create, install, withdraw, reinstate, delete, bundle add/remove; metadata events on update |
| `dspace-api/.../content/CollectionServiceImpl.java` | `DetailType` on create, delete, item add/remove, template removal; metadata events on update |
| `dspace-api/.../content/CommunityServiceImpl.java` | `DetailType` on create, delete, collection and sub-community add/remove; metadata events on update |
| `dspace-api/.../content/BundleServiceImpl.java` | `DetailType` on create, delete, bitstream add/remove; two added item-scoped events so bitstream changes can be traced to their item |
| `dspace-api/.../content/BitstreamServiceImpl.java` | Bitstream checksum recorded on create/register/delete; item-scoped events; private `getItem` helper |
| `dspace-api/.../content/SiteServiceImpl.java` | Metadata events on update |
| `dspace-api/.../content/InstallItemServiceImpl.java` | `DetailType.HANDLE` on the INSTALL event |
| `dspace-api/.../eperson/GroupServiceImpl.java` | `DetailType` on membership changes and delete; metadata events on update |
| `dspace-api/.../event/TestConsumer.java` | `getDetail()` now returns an `EventDetail` |
| `dspace-api/.../discovery/IndexEventConsumer.java` | Reads the DSO type out of the typed detail |
| `dspace-api/.../rdf/RDFConsumer.java` | Reads the handle out of the typed detail |
| `dspace-api/.../authority/indexer/AuthorityConsumer.java` | Compares against the detail object rather than the `EventDetail` wrapper |

Two notes on how this differs from upstream:

- **The service classes were ported by hand, not copied.** The DSpace 10 versions of these files
  also carry changes that have nothing to do with auditing and do not belong in a 9.2 tree:
  `Strings.CS.equals` from a newer commons-lang3, `Constants.LICENSE_BUNDLE_NAME`, and a rework of
  the date-issued logic in `InstallItemServiceImpl`. Only the detail-related hunks were taken. On
  upgrading, these edits become redundant and should simply be dropped in favour of upstream.
- **Two upstream defects were not reproduced.** `AuthorityConsumer` and `RDFConsumer` compare the
  event detail against a string literal. Upstream left those comparisons in place after
  `getDetail()` changed type, which makes them silently always false. Both compare against the
  detail object here. (The `AuthorityConsumer` `"ARCHIVED: true"` branch is dead code either way —
  nothing in the codebase emits that detail.)

Because upstream kept deprecated `Object`-detail constructors, roughly seventy call sites that pass
a plain string still compile untouched. Only the events that matter for auditing were given explicit
detail types.

### Audit backport (upstream PR #11072)

| File | Nature of the edit |
| --- | --- |
| `dspace-server-webapp/.../utils/DSpaceObjectUtils.java` | Added `findDSpaceObject(context, uuid, type)` |
| `dspace-server-webapp/.../patch/operation/DSpaceObjectMetadataReplaceOperation.java` | Record `MetadataEvent.MODIFY` when a patch replaces a value |

### Configuration

| File | Nature of the edit |
| --- | --- |
| `dspace/config/dspace.cfg` | `audit` added to `event.dispatcher.default.consumers`; `event.consumer.audit.*` registered |
| `dspace/config/modules/rest.cfg` | Exposes `audit.enabled` and `audit.context-menu-entry.enabled` to the frontend |
| `dspace/config/modules/usage-statistics.cfg` | Added `otcloud-statistics.metadata-table.max-items` |
| `dspace/config/log4j2.xml` | Dedicated `AUDIT` appender and `org.dspace.app.audit.event` logger |
| `docker-compose.yml` | Pre-creates the `audit` Solr core |
| `dspace/src/main/docker/dspace-solr/Dockerfile` | Copies the `audit` configset |

## New files

Added from DSpace 10, unmodified — delete these on upgrade and take upstream's:

- `dspace-api/.../event/EventDetail.java`, `event/DetailType.java`
- `dspace-api/.../app/audit/` (`AuditService`, `AuditSolrServiceImpl`, `AuditConsumer`, `AuditEvent`,
  `MetadataEvent`, `factory/`)
- `dspace-api/.../discovery/SolrDocumentFactory.java`, `DefaultSolrDocumentFactory.java`
- `dspace-server-webapp/.../model/AuditEventRest.java`, `model/hateoas/AuditEventResource.java`,
  `converter/AuditEventConverter.java`, and the four `AuditEvent*Repository` classes
- `dspace/config/modules/audit.cfg`, `dspace/solr/audit/**`
- **`dspace/config/spring/api/audit-services.xml`** — upstream declares these beans in
  `solr-services.xml` and `core-factory-services.xml`. They were put in a separate file so the
  upstream Spring configuration stays pristine. **This file must be deleted on upgrade**, otherwise
  every audit bean is registered twice. The bean ids match upstream's deliberately, so a missed
  deletion produces a deterministic definition override rather than an ambiguous autowire failure.

Local work, to be carried forward:

- `dspace-api/.../otcloud/statistics/` — `OTCloudStatisticsService` (JSON Facet aggregation over the
  usage statistics core), `MetadataUsageService`, and their value types
- `dspace-api/.../otcloud/audit/` — `StaffActivityService` and its value types
- `dspace-server-webapp/.../OTCloudStatsController`, `OTCloudDashboardController`,
  `OTCloudAuditReportController` and their REST models under `model/` and `model/hateoas/`
- `dspace/config/spring/api/otcloud-statistics-services.xml`

## Deliberately not backported

- **`SolrCoreExportImport`** and its script configurations (four classes, plus `scripts.xml` entries
  and the `log4j-slf4j-impl` exclusions those needed in five `pom.xml` files). This is tooling for
  dumping and reloading a Solr core; the audit trail works without it. Consequence: **there is no
  built-in export/backup tool for the audit core.** Back it up as part of the Solr data directory.
  Because this was skipped, no `pom.xml` in the tree carries a local edit.
- **The audit integration tests** (`AuditEventRestRepositoryIT`, `AuditEventMatcher`,
  `AuditEventBuilder`, `MockAuditSolrService`, and the accompanying `AbstractBuilder` and
  `InstallItemTest` changes). `MockAuditSolrService` needs an embedded `audit` core in the test
  harness, and the test `local.cfg` excludes the audit consumer anyway, so adding them risked
  breaking the existing suite for no coverage of our own code.
- **The Angular side (dspace-angular PR #4576).** The backend endpoints exist; the admin audit
  overview and per-item audit pages have not been built in the companion frontend.

## Operational notes

- **Auditing is off by default.** Set `audit.enabled = true` in `config/modules/audit.cfg`, and make
  sure the `audit` Solr core has been deployed.
- **Only archived items are fully audited by default.** `audit.item.in-workflow` and
  `audit.item.in-workspace` are both false, so changes to items still in submission or workflow are
  not recorded — except CREATE, which is always recorded.
- **Leave those two flags off unless you need them.** When either is enabled, upstream's
  `isAuditableItem` calls `poolTaskService.findAll()` or `workspaceItemService.findAll()` **on every
  audited event**, loading every pool task or workspace item each time. On a busy repository that is
  a serious performance problem.
- **The staff activity report counts distinct objects, not audit rows.** The audit core writes one
  row per changed metadata value, so an edit touching five fields writes five rows. Counting rows
  would overstate the work fivefold.
- **The usage-by-metadata report is capped and says so.** See
  `otcloud-statistics.metadata-table.max-items`. When the cap is hit, responses carry
  `truncated: true` and the counts are a lower bound over the most viewed items, not
  repository-wide totals. Surface that in any UI built on it.
