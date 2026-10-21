# Nuxeo Audit Forwarder

Nuxeo LTS 2023 add-on that forwards **audit events** (logins, document changes, permission changes, downloads and
any other audited event) to security and data platforms:

| Sink type | Destination | Transport | Authentication |
|---|---|---|---|
| `splunk` | Splunk HTTP Event Collector | HTTPS | HEC token |
| `qradar` | IBM QRadar | syslog TLS/TCP/UDP (LEEF 2.0 or CEF), or HTTPS to an HTTP Receiver log source | TLS client certificate |
| `sentinel` | Microsoft Sentinel / Azure Monitor Logs Ingestion API | HTTPS | Microsoft Entra ID app (client credentials) |
| `syslog` | Any RFC 5424 receiver (JSON, CEF or LEEF body) | TLS (default), TCP, UDP | TLS client certificate |
| `webhook` | Any HTTPS endpoint | HTTPS | Bearer token, basic, custom header or OAuth 2.0 client credentials; optional HMAC-SHA256 signature |
| `sqs` | Amazon SQS (standard or FIFO) | AWS SDK v2 | Nuxeo AWS configuration, default AWS chain, static keys, optional STS role |

Destinations and credentials are set in `nuxeo.conf`. Administrators pick the forwarded events, check delivery and
send a test event from the Web UI (**Administration > Audit Forwarder**) or through Automation operations.

## How it works

- Nuxeo writes every audit entry to the `audit/audit` stream. The forwarder reads that stream with **one
  computation per sink**, each in its own consumer group (`audit/forwarder-<sink>`), beside Nuxeo's own audit
  writer. Offsets are independent: a sink that is down holds back only itself and resumes where it stopped.
- **At-least-once delivery.** A failed batch is retried with exponential backoff until it is delivered (default),
  so receivers can see duplicates. Every event carries a stable `id` (a fingerprint of the entry) to deduplicate on;
  the SQS sink uses it as the FIFO deduplication id. With `continueOnFailure=true` a batch is dropped and counted
  after `retry.maxAttempts`.
- Events are filtered three times: the selection saved by an administrator (or `events.default` until one is
  saved), the sink's own optional `events` list, and `exclude.principals` (by default `system`).
- Times are UTC, ISO-8601 with milliseconds. Each event is stamped with `source.host`.

Canonical event (JSON body of the webhook, SQS and JSON-syslog sinks, and the `event` of a Splunk envelope):

```json
{
  "id": "4c8d1f0e3a5b7c9d2e4f6a8b0c1d3e5f",
  "eventId": "documentModified",
  "category": "eventDocumentCategory",
  "eventDate": "2026-10-15T08:30:00.123Z",
  "logDate": "2026-10-15T08:30:00.130Z",
  "principal": "jdoe",
  "comment": "…",
  "docUUID": "…",
  "docPath": "/default-domain/workspaces/finance/report",
  "docType": "File",
  "docLifeCycle": "project",
  "repositoryId": "default",
  "extended": { "…": "…" },
  "sourceHost": "nuxeo-node-1"
}
```

Empty fields are omitted.

## Requirements

- Nuxeo LTS 2023 (package target `[2023.0,2024.0)`), Java 17. Built and tested against `nuxeo-parent` 2023.54.
  The LTS 2025 version is on the `2025` branch.
- The Web UI page needs the Nuxeo Web UI package; without it the add-on runs and the page is simply absent.
- The package bundles `nuxeo-runtime-aws` with the AWS SDK for Java 1.x core it needs on LTS 2023, and the AWS SDK
  for Java 2.x (about 7 MB in all), which the server distribution does not ship. The SQS sink uses SDK 2.x and takes
  credentials, region and TLS from the Nuxeo AWS configuration as on LTS 2025.

## Build and install

```sh
mvn install
nuxeoctl mp-install nuxeo-audit-forwarder-package/target/nuxeo-audit-forwarder-package-1.0.0-SNAPSHOT.zip
```

The build needs access to the Nuxeo LTS 2023 Maven artifacts. `TestSqsSink` runs against LocalStack with
Testcontainers; it is skipped without Docker or without a `LOCALSTACK_AUTH_TOKEN` environment variable (current
LocalStack images require one). Override the image with `-Dlocalstack.image=...`.

## Configuration (`nuxeo.conf`)

Global keys are declared, with their defaults, in
`nuxeo-audit-forwarder-core/src/main/resources/OSGI-INF/audit-forwarder-config.xml`.

| Key | Default | Meaning |
|---|---|---|
| `maretha.audit.forwarder.enabled` | `false` | Master switch. When false nothing is read from the stream. |
| `maretha.audit.forwarder.sinks` | *(empty)* | Comma-separated sink names, in order. Names use letters, digits, `-`, `_`. |
| `maretha.audit.forwarder.events.default` | 17 common events | Selection used until an administrator saves one. `*` means every audited event. |
| `maretha.audit.forwarder.exclude.principals` | `system` | Users whose events are never forwarded. |
| `maretha.audit.forwarder.source.host` | host name | Stamped on every event. |
| `maretha.audit.forwarder.settings.cache.seconds` | `30` | How long each node caches the saved selection. |
| `maretha.audit.forwarder.stream.batch.size` | `100` | Records per stream batch. |
| `maretha.audit.forwarder.stream.batch.threshold.ms` | `500` | Maximum wait to fill a batch. |
| `maretha.audit.forwarder.health.check.enabled` | `false` | Adds the `auditForwarderStatus` probe to `/runningstatus`. |

Each sink is configured under `maretha.audit.forwarder.sink.<name>.`. A missing required key stops the startup
with a message that names the key (never its value). Settings found for a name missing from `sinks` are logged as
a warning.

### Settings shared by every sink

| Key | Default | Meaning |
|---|---|---|
| `type` | *(required)* | `splunk`, `qradar`, `sentinel`, `syslog`, `webhook`, `sqs`, or a contributed type. |
| `enabled` | `true` | `false` keeps the settings but does not start the sink. |
| `events` | *(all selected)* | Comma-separated subset of the global selection for this sink only. |
| `batch.size` | `100` | Events per delivery. |
| `continueOnFailure` | `false` | `true` drops a batch after `retry.maxAttempts`; `false` retries until delivered. |
| `retry.maxAttempts` | `5` | Only with `continueOnFailure=true`. |
| `retry.initialDelay.ms` / `retry.maxDelay.ms` | `1000` / `60000` | Exponential backoff bounds. |
| `connect.timeout.ms` / `request.timeout.ms` | `10000` / `30000` | Network timeouts. |
| `tls.truststore.path` / `.password` / `.type` | JVM default / — / `PKCS12` | Private CA for HTTPS and syslog TLS. |
| `tls.keystore.path` / `.password` / `.type` | — / — / `PKCS12` | Client certificate (mutual TLS). |

Certificate and host name verification cannot be turned off. Redirects are not followed. Plain `http://` URLs work
but log a warning at startup.

### `splunk`

| Key | Default | Meaning |
|---|---|---|
| `url` | *(required)* | Full HEC endpoint, e.g. `https://splunk.example.com:8088/services/collector/event`. |
| `token` | *(required)* | HEC token, sent as `Authorization: Splunk <token>`. |
| `channel` | — | `X-Splunk-Request-Channel`, for HEC tokens with indexer acknowledgement. Acknowledgements are not polled. |
| `index` | token default | HEC `index`. |
| `source` / `sourcetype` | `nuxeo:audit` / `_json` | HEC metadata. |
| `host` | event `sourceHost` | HEC `host`. |

`time`, `host`, `source`, `sourcetype` and `index` are HEC metadata beside the event, not fields inside it.

### `qradar`

| Key | Default | Meaning |
|---|---|---|
| `transport` | `syslog` | `syslog` or `https`. |
| `format` | `leef` | `leef` (LEEF 2.0, tab-delimited, `devTime` with explicit `devTimeFormat`) or `cef`. |
| `host`, `port`, `protocol`, `framing`, `facility`, `app.name` | see `syslog` | For `transport=syslog`. Defaults: `protocol=tls`, `framing=newline`. |
| `url` | *(required for https)* | HTTP Receiver log source URL. One event per line, `text/plain`. |
| `headers.<Name>` | — | Extra HTTP headers for `transport=https`. |

The syslog message is an RFC 5424 header followed by a space and the LEEF/CEF event.

### `sentinel`

Sends to the Azure Monitor Logs Ingestion API:
`POST {endpoint}/dataCollectionRules/{dcr.id}/streams/{stream}?api-version=2023-01-01`, gzip-compressed, in
requests under 1 MB, with a bearer token obtained from Microsoft Entra ID (client credentials, cached until a minute
before expiry, refreshed after a 401).

| Key | Default | Meaning |
|---|---|---|
| `endpoint` | *(required)* | The DCR logs ingestion endpoint, or a data collection endpoint (DCE). |
| `dcr.id` | *(required)* | The DCR immutable id (`dcr-…`). |
| `stream` | *(required)* | Stream declared in the DCR, e.g. `Custom-NuxeoAudit`. |
| `tenant.id` / `client.id` / `client.secret` | *(required)* | App registration with the **Monitoring Metrics Publisher** role on the DCR. |
| `authority` | `https://login.microsoftonline.com` | Change for sovereign clouds. |
| `scope` | `https://monitor.azure.com/.default` | Change for sovereign clouds. |
| `api.version` | `2023-01-01` | |
| `gzip` | `true` | |
| `max.request.bytes` | `1000000` | Upper bound of one request body before compression. |

Stream declaration for the DCR (the rows the sink sends):

```json
"streamDeclarations": {
  "Custom-NuxeoAudit": {
    "columns": [
      { "name": "TimeGenerated", "type": "datetime" },
      { "name": "AuditId", "type": "string" },
      { "name": "EventId", "type": "string" },
      { "name": "Category", "type": "string" },
      { "name": "Principal", "type": "string" },
      { "name": "Comment", "type": "string" },
      { "name": "DocUUID", "type": "string" },
      { "name": "DocPath", "type": "string" },
      { "name": "DocType", "type": "string" },
      { "name": "DocLifeCycle", "type": "string" },
      { "name": "RepositoryId", "type": "string" },
      { "name": "EventDate", "type": "datetime" },
      { "name": "LogDate", "type": "datetime" },
      { "name": "SourceHost", "type": "string" },
      { "name": "Extended", "type": "dynamic" }
    ]
  }
}
```

With `"transformKql": "source"` and a custom table `NuxeoAudit_CL` with the same columns.

### `syslog`

| Key | Default | Meaning |
|---|---|---|
| `host` | *(required)* | Receiver host. With TLS the certificate must match it. |
| `port` | `6514` for TLS, `514` otherwise | |
| `protocol` | `tls` | `tls` (RFC 5425), `tcp` (RFC 6587), `udp` (RFC 5426). |
| `framing` | `octet_counting` | `octet_counting` (`<length> <message>`) or `newline`. TCP and TLS only. |
| `format` | `json` | Message body: `json`, `cef` or `leef`. |
| `facility` | `13` (log audit) | 0–23. Severity follows the event: warning for `loginFailed`, notice for permission changes, deletions, retention, legal-hold and user/group changes, informational otherwise. |
| `app.name` | `nuxeo-audit` | RFC 5424 APP-NAME. The MSGID is the event id. |
| `udp.maxBytes` | `8192` | Longer UDP datagrams are truncated. |

### `webhook`

| Key | Default | Meaning |
|---|---|---|
| `url` | *(required)* | Endpoint receiving `POST` requests. |
| `auth.type` | *(required)* | `bearer`, `basic`, `header`, `oauth2`, or `none` to opt out explicitly. |
| `auth.token` | | For `bearer`. |
| `auth.username` / `auth.password` | | For `basic`. |
| `auth.header.name` / `auth.header.value` | | For `header`, e.g. an API key header. |
| `oauth2.token.url` / `oauth2.client.id` / `oauth2.client.secret` | | For `oauth2` (client credentials grant). |
| `oauth2.scope` | — | |
| `oauth2.client.auth` | `post` | `post` (credentials in the form) or `basic` (Authorization header). |
| `format` | `json` | `json` (one array per request) or `ndjson`. |
| `hmac.secret` | — | Enables the signature. |
| `hmac.header` | `X-Audit-Forwarder-Signature` | Carries `sha256=<hex>`. |
| `headers.<Name>` | — | Static headers. |

The signature is an HMAC-SHA256 of `<timestamp>.<body>`, where the timestamp (Unix seconds) travels in
`X-Audit-Forwarder-Timestamp`. A receiver checks it like this and should reject old timestamps:

```python
expected = "sha256=" + hmac.new(secret, f"{timestamp}.{raw_body}".encode(), hashlib.sha256).hexdigest()
ok = hmac.compare_digest(expected, request.headers["X-Audit-Forwarder-Signature"])
```

### `sqs`

| Key | Default | Meaning |
|---|---|---|
| `queue.url` | *(required)* | A URL ending in `.fifo` enables FIFO group and deduplication ids. |
| `region` | Nuxeo AWS configuration | Falls back to `nuxeo.aws.region` (`aws` template), then the AWS default chain. |
| `aws.config` | — | Id of a Nuxeo AWS configuration (`nuxeo-runtime-aws`) for credentials, region and TLS. |
| `access.key.id` / `secret.access.key` | — | Static keys; otherwise the Nuxeo AWS configuration, then the default AWS chain (environment, profile, instance role). |
| `role.arn` / `role.external.id` / `role.session.name` | — / — / `nuxeo-audit-forwarder` | Role assumed through AWS STS. |
| `endpoint` / `sts.endpoint` | — | Endpoint overrides (VPC endpoints, LocalStack). |
| `proxy` | `false` | Use the Nuxeo HTTP proxy settings. |
| `fifo.group.by` | `docUUID` | `docUUID`, `principal`, `eventId`, `repositoryId` or `single`. Events without a value share one group. |
| `max.batch.bytes` | `262144` | Upper bound of one `SendMessageBatch`. |

One message per event, the canonical JSON as body, with `eventId`, `category` and `repositoryId` as message
attributes. Entries the queue rejects are tried up to three times in all before the batch fails.

### Example

```properties
maretha.audit.forwarder.enabled=true
maretha.audit.forwarder.sinks=splunk,siem,queue
maretha.audit.forwarder.health.check.enabled=true

maretha.audit.forwarder.sink.splunk.type=splunk
maretha.audit.forwarder.sink.splunk.url=https://splunk.example.com:8088/services/collector/event
maretha.audit.forwarder.sink.splunk.token=<encrypted value>
maretha.audit.forwarder.sink.splunk.index=nuxeo

maretha.audit.forwarder.sink.siem.type=syslog
maretha.audit.forwarder.sink.siem.host=siem.example.com
maretha.audit.forwarder.sink.siem.format=cef
maretha.audit.forwarder.sink.siem.tls.truststore.path=/etc/nuxeo/siem-ca.p12
maretha.audit.forwarder.sink.siem.tls.truststore.password=<encrypted value>

maretha.audit.forwarder.sink.queue.type=sqs
maretha.audit.forwarder.sink.queue.queue.url=https://sqs.eu-west-1.amazonaws.com/123456789012/nuxeo-audit.fifo
maretha.audit.forwarder.sink.queue.region=eu-west-1
maretha.audit.forwarder.sink.queue.events=loginFailed,documentSecurityUpdated,documentRemoved
```

`examples/` has a configuration and local stand-in receivers for trying every sink on a workstation.

## Administration

Administrators only (other users get HTTP 403). No operation returns a credential.

| Operation | Params | Returns |
|---|---|---|
| `AuditForwarder.GetSettings` | — | `enabled`, the selected `events`, who saved them and when. |
| `AuditForwarder.SaveSettings` | `events` (list) | The saved settings. `*` selects every audited event. |
| `AuditForwarder.ListAuditableEvents` | — | Event names Nuxeo audits. |
| `AuditForwarder.ListSinks` | — | Per sink: type, target (no credentials), sent, failed attempts, dropped, last success, last error, failing. |
| `AuditForwarder.TestSink` | `sink` | Sends one synthetic `auditForwarderTest` event straight to the sink and reports the outcome. |

The selection is stored in one hidden document, `/audit-forwarder-settings`, readable and writable by the
`administrators` group only. Each node rereads it every `settings.cache.seconds`. Counters are per node and reset
at restart.

Probe `auditForwarderStatus` fails while the latest delivery attempt of any sink failed, and succeeds when the
forwarder is disabled. It appears in `/nuxeo/runningstatus` with `health.check.enabled=true`.

## Extending

Contribute a sink type and reference it with `type=<name>`:

```xml
<extension target="io.maretha.audit.forwarder.AuditForwarderService" point="sinkTypes">
  <sinkType name="mysiem" factory="com.example.MySiemSink$Factory" />
</extension>
```

The factory implements `io.maretha.audit.forwarder.sink.AuditSinkFactory` and receives the sink's settings.

## Security notes

These are the known trade-offs. Read them before deploying.

- **Audit data leaves Nuxeo.** Events carry user names, document paths, comments and extended information; the
  `search` event's extended information includes search queries. Forward only the events the destination needs,
  and narrow sensitive sinks with their `events` setting.
- **Secrets live in `nuxeo.conf`.** Store them as Nuxeo encrypted values: sink settings are read through the runtime
  properties, which decrypt them. This project's tests do not exercise encrypted values. Never put credentials in
  the settings document; it only holds event names.
- **Duplicates are possible** (at-least-once). Deduplicate on `id`.
- **A long outage can still lose events.** A sink resumes from its stream offset only while the audit stream
  retains the records. Size the stream retention (Kafka or Chronicle) for the longest outage you accept. With
  `continueOnFailure=true`, batches are dropped after the retries.
- **Syslog over UDP or plain TCP is neither authenticated nor encrypted**, and UDP is lossy. TCP and TLS have no
  delivery acknowledgement: the sink reconnects when the receiver closes the connection, but messages written while
  a connection breaks can be lost. Prefer TLS, or an HTTPS sink when loss matters.
- **Plain `http://` URLs are accepted** (with a startup warning) for tests; they expose tokens on the network.
- **The QRadar HTTPS transport sends no credential of its own**; rely on the TLS client certificate and the
  receiver's allow list, or add `headers.*` for an authenticating proxy.
- **Error details reach administrators.** Up to 300 characters of a receiver's error response are logged and shown
  on the administration page. Host and path are shown; query strings are not.
- **Startup fails on invalid configuration**, by design, rather than running without forwarding.
- The test button emits a real event (`auditForwarderTest`) into the destination.

## Validation limits

- Automated tests: formatters, configuration validation, retry and batching, HTTP sinks against a local stub server
  (Splunk HEC, Sentinel token and upload, webhook with every authentication mode and HMAC, QRadar HTTPS), syslog
  over TCP, UDP and mutual TLS against local sockets, SQS against LocalStack (standard and FIFO), and a Nuxeo
  runtime test of the stream path, sink isolation, the event selection and the operations.
- On LTS 2023 the automated tests pass against `nuxeo-parent` 2023.54, with the SQL audit backend; `TestSqsSink`
  has not yet run against LocalStack on this branch.
- Manual check on a Nuxeo 2025.26 server with the LTS 2025 build (in-memory stream and audit backend): every sink
  type delivered events to local stand-in receivers and LocalStack, a receiver restart caused no loss, and the probe
  and operations behaved as described. Not yet repeated on an LTS 2023 server.
- Not tested against real Splunk, IBM QRadar or Microsoft Sentinel instances, against Kafka-backed streams, in a
  cluster, or with Nuxeo encrypted configuration values. The Web UI page has not been exercised in a browser.

## Provenance

Written by Maretha.

## License

Apache License 2.0, see `LICENSE` and `NOTICE`.
