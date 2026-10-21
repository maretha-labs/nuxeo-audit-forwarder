/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.transport.HttpStatusException;
import io.maretha.audit.forwarder.transport.HttpTransport;
import io.maretha.audit.forwarder.transport.OAuth2ClientCredentials;

/**
 * Microsoft Sentinel through the Azure Monitor Logs Ingestion API:
 * {@code POST {endpoint}/dataCollectionRules/{dcr.id}/streams/{stream}?api-version=2023-01-01} with a Microsoft Entra
 * ID bearer token from the client credentials grant. The body is a JSON array whose rows must match the stream
 * declared in the data collection rule; requests stay under 1 MB.
 */
public class SentinelSink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new SentinelSink(settings);
        }
    }

    protected final URI uri;

    protected final boolean gzip;

    protected final int maxRequestBytes;

    protected final HttpTransport http;

    protected final OAuth2ClientCredentials oauth;

    public SentinelSink(SinkSettings settings) {
        URI endpoint = settings.requireHttpUri("endpoint");
        String dcrId = settings.require("dcr.id");
        String stream = settings.require("stream");
        String apiVersion = settings.get("api.version", "2023-01-01");
        uri = URI.create(StringUtils.removeEnd(endpoint.toString(), "/") + "/dataCollectionRules/" + dcrId
                + "/streams/" + stream + "?api-version=" + apiVersion);
        gzip = settings.getBoolean("gzip", true);
        maxRequestBytes = settings.getInt("max.request.bytes", 1_000_000);
        http = new HttpTransport(settings);
        String authority = StringUtils.removeEnd(settings.get("authority", "https://login.microsoftonline.com"), "/");
        URI tokenUri = URI.create(authority + "/" + settings.require("tenant.id") + "/oauth2/v2.0/token");
        oauth = new OAuth2ClientCredentials(http, tokenUri, settings.require("client.id"),
                settings.require("client.secret"), settings.get("scope", "https://monitor.azure.com/.default"),
                false);
        HttpTransport.warnIfPlainHttp(settings, uri);
    }

    @Override
    public String target() {
        return HttpTransport.describe(uri);
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        for (String body : chunks(events)) {
            byte[] data = body.getBytes(UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + oauth.token());
            headers.put("Content-Type", "application/json");
            if (gzip) {
                headers.put("Content-Encoding", "gzip");
                data = HttpTransport.gzip(data);
            }
            try {
                http.post(uri, headers, data);
            } catch (HttpStatusException e) {
                if (e.status() == 401) {
                    oauth.invalidate();
                }
                throw e;
            }
        }
    }

    /** @return JSON arrays of rows, each under the request size limit (a single oversized row goes alone) */
    protected List<String> chunks(List<AuditEvent> events) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder("[");
        for (AuditEvent event : events) {
            String row = EventJson.toJson(row(event));
            if (current.length() > 1 && current.length() + row.length() + 2 > maxRequestBytes) {
                chunks.add(current.append(']').toString());
                current = new StringBuilder("[");
            }
            if (current.length() > 1) {
                current.append(',');
            }
            current.append(row);
        }
        if (current.length() > 1) {
            chunks.add(current.append(']').toString());
        }
        return chunks;
    }

    /** Column names for the custom table; see the README for the matching stream declaration. */
    protected Map<String, Object> row(AuditEvent event) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("TimeGenerated", EventJson.iso(event.time()));
        row.put("AuditId", event.id());
        row.put("EventId", event.eventId());
        row.put("Category", event.category());
        row.put("Principal", event.principal());
        row.put("Comment", event.comment());
        row.put("DocUUID", event.docUUID());
        row.put("DocPath", event.docPath());
        row.put("DocType", event.docType());
        row.put("DocLifeCycle", event.docLifeCycle());
        row.put("RepositoryId", event.repositoryId());
        row.put("EventDate", EventJson.iso(event.eventDate()));
        row.put("LogDate", EventJson.iso(event.logDate()));
        row.put("SourceHost", event.sourceHost());
        row.put("Extended", EventJson.safeValue(event.extended()));
        row.values().removeIf(v -> v == null);
        return row;
    }
}
