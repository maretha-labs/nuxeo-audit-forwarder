/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.maretha.audit.forwarder.StubHttpServer.Request;
import io.maretha.audit.forwarder.StubHttpServer.Response;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.sink.AuditSink;
import io.maretha.audit.forwarder.sink.QRadarSink;
import io.maretha.audit.forwarder.sink.SentinelSink;
import io.maretha.audit.forwarder.sink.SinkException;
import io.maretha.audit.forwarder.sink.SplunkHecSink;
import io.maretha.audit.forwarder.sink.WebhookSink;

public class TestHttpSinks {

    static final List<AuditEvent> EVENTS = List.of(Fixtures.event("documentCreated"),
            Fixtures.event("documentModified"));

    protected StubHttpServer server;

    @Before
    public void startServer() throws Exception {
        server = new StubHttpServer();
    }

    @After
    public void stopServer() {
        server.close();
    }

    static JsonNode json(String text) throws Exception {
        return EventJson.MAPPER.readTree(text);
    }

    @Test
    public void splunkSendsHecEnvelopesWithMetadataBesideTheEvent() throws Exception {
        AuditSink sink = new SplunkHecSink(Fixtures.settings("hec", "url", server.url("/services/collector/event"),
                "token", "hec-token", "channel", "chan-1", "index", "nuxeo", "sourcetype", "nuxeo:audit:json"));
        sink.send(EVENTS);
        Request request = server.requests.get(0);
        assertEquals("Splunk hec-token", request.header("Authorization"));
        assertEquals("chan-1", request.header("X-Splunk-Request-Channel"));
        String[] lines = request.text().split("\n");
        assertEquals(2, lines.length);
        JsonNode first = json(lines[0]);
        assertEquals("nuxeo", first.get("index").asText());
        assertEquals("nuxeo:audit:json", first.get("sourcetype").asText());
        assertEquals("nuxeo:audit", first.get("source").asText());
        assertEquals("nuxeo-node-1", first.get("host").asText());
        assertEquals(Fixtures.TIME.toEpochMilli() / 1000.0, first.get("time").asDouble(), 0.0001);
        // plain decimal seconds on the wire, no exponent
        assertTrue(lines[0], lines[0].startsWith("{\"time\":" + Fixtures.TIME.getEpochSecond() + ".123,"));
        assertEquals("documentCreated", first.get("event").get("eventId").asText());
        assertFalse(first.get("event").has("index"));
    }

    @Test
    public void errorsReportStatusWithoutCredentials() throws Exception {
        server.respond("/services/collector/event", new Response(403, "{\"text\":\"Invalid token\",\"code\":4}"));
        AuditSink sink = new SplunkHecSink(Fixtures.settings("hec", "url",
                server.url("/services/collector/event") + "?sig=query-secret", "token", "hec-token"));
        SinkException e = assertThrows(SinkException.class, () -> sink.send(EVENTS));
        assertTrue(e.getMessage(), e.getMessage().contains("HTTP 403"));
        assertTrue(e.getMessage(), e.getMessage().contains("Invalid token"));
        assertFalse(e.getMessage(), e.getMessage().contains("hec-token"));
        assertFalse(e.getMessage(), e.getMessage().contains("query-secret"));
    }

    @Test
    public void webhookBearerWithHmacSignature() throws Exception {
        AuditSink sink = new WebhookSink(Fixtures.settings("hook", "url", server.url("/hook"), "auth.type", "bearer",
                "auth.token", "tok-1", "hmac.secret", "shh", "headers.X-Tenant", "t1"));
        sink.send(EVENTS);
        Request request = server.requests.get(0);
        assertEquals("Bearer tok-1", request.header("Authorization"));
        assertEquals("t1", request.header("X-Tenant"));
        assertEquals("application/json", request.header("Content-Type"));
        JsonNode body = json(request.text());
        assertEquals(2, body.size());
        assertEquals("documentModified", body.get(1).get("eventId").asText());
        String timestamp = request.header(WebhookSink.TIMESTAMP_HEADER);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("shh".getBytes(UTF_8), "HmacSHA256"));
        String expected = "sha256="
                + HexFormat.of().formatHex(mac.doFinal((timestamp + "." + request.text()).getBytes(UTF_8)));
        assertEquals(expected, request.header("X-Audit-Forwarder-Signature"));
    }

    @Test
    public void webhookBasicAuthAndNdjson() throws Exception {
        AuditSink sink = new WebhookSink(Fixtures.settings("hook", "url", server.url("/hook"), "auth.type", "basic",
                "auth.username", "svc", "auth.password", "p:w", "format", "ndjson"));
        sink.send(EVENTS);
        Request request = server.requests.get(0);
        assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:p:w".getBytes(UTF_8)),
                request.header("Authorization"));
        assertEquals("application/x-ndjson", request.header("Content-Type"));
        String[] lines = request.text().split("\n");
        assertEquals(2, lines.length);
        assertEquals("documentCreated", json(lines[0]).get("eventId").asText());
        assertNull(request.header(WebhookSink.TIMESTAMP_HEADER));
    }

    @Test
    public void webhookCustomHeaderAuth() throws Exception {
        AuditSink sink = new WebhookSink(Fixtures.settings("hook", "url", server.url("/hook"), "auth.type", "header",
                "auth.header.name", "X-Api-Key", "auth.header.value", "k1"));
        sink.send(EVENTS);
        assertEquals("k1", server.requests.get(0).header("X-Api-Key"));
    }

    @Test
    public void webhookOAuth2CachesTokenAndRefreshesAfter401() throws Exception {
        server.respond("/token", new Response(200, "{\"access_token\":\"at-1\",\"expires_in\":3600}"),
                new Response(200, "{\"access_token\":\"at-2\",\"expires_in\":3600}"));
        server.respond("/hook", new Response(200, "{}"), new Response(200, "{}"), new Response(401, "{}"),
                new Response(200, "{}"));
        AuditSink sink = new WebhookSink(Fixtures.settings("hook", "url", server.url("/hook"), "auth.type", "oauth2",
                "oauth2.token.url", server.url("/token"), "oauth2.client.id", "cid", "oauth2.client.secret", "cs",
                "oauth2.scope", "audit.write"));
        sink.send(EVENTS);
        sink.send(EVENTS);
        assertEquals(1, server.requestsTo("/token").size());
        String form = server.requestsTo("/token").get(0).text();
        assertTrue(form, form.contains("grant_type=client_credentials"));
        assertTrue(form, form.contains("client_id=cid"));
        assertTrue(form, form.contains("scope=audit.write"));
        assertThrows(SinkException.class, () -> sink.send(EVENTS));
        sink.send(EVENTS);
        assertEquals(2, server.requestsTo("/token").size());
        List<Request> hooks = server.requestsTo("/hook");
        assertEquals("Bearer at-1", hooks.get(2).header("Authorization"));
        assertEquals("Bearer at-2", hooks.get(3).header("Authorization"));
    }

    @Test
    public void sentinelUploadsGzippedRowsToTheDcrStream() throws Exception {
        server.respond("/tenant-1/oauth2/v2.0/token", new Response(200, "{\"access_token\":\"aad\",\"expires_in\":3599}"));
        server.respond("/dataCollectionRules/dcr-abc/streams/Custom-NuxeoAudit", new Response(204, ""));
        AuditSink sink = new SentinelSink(Fixtures.settings("sentinel", "endpoint", server.url(""), "dcr.id",
                "dcr-abc", "stream", "Custom-NuxeoAudit", "tenant.id", "tenant-1", "client.id", "app-1",
                "client.secret", "s3cret", "authority", server.url("")));
        sink.send(EVENTS);
        String form = server.requestsTo("/tenant-1/oauth2/v2.0/token").get(0).text();
        assertTrue(form, form.contains("scope=https%3A%2F%2Fmonitor.azure.com%2F.default"));
        Request upload = server.requestsTo("/dataCollectionRules/dcr-abc/streams/Custom-NuxeoAudit").get(0);
        assertEquals("api-version=2023-01-01", upload.query());
        assertEquals("Bearer aad", upload.header("Authorization"));
        assertEquals("gzip", upload.header("Content-Encoding"));
        JsonNode rows = json(upload.text());
        assertEquals(2, rows.size());
        assertEquals("2026-10-15T08:30:00.123Z", rows.get(0).get("TimeGenerated").asText());
        assertEquals("documentCreated", rows.get(0).get("EventId").asText());
        assertEquals("test", rows.get(0).get("Extended").get("reason").asText());
    }

    @Test
    public void sentinelSplitsRequestsAtTheSizeLimit() throws Exception {
        server.respond("/t/oauth2/v2.0/token", new Response(200, "{\"access_token\":\"aad\"}"));
        AuditSink sink = new SentinelSink(Fixtures.settings("sentinel", "endpoint", server.url(""), "dcr.id", "d",
                "stream", "s", "tenant.id", "t", "client.id", "c", "client.secret", "x", "authority", server.url(""),
                "gzip", "false", "max.request.bytes", "700"));
        sink.send(List.of(Fixtures.event("a"), Fixtures.event("b"), Fixtures.event("c")));
        List<Request> uploads = server.requestsTo("/dataCollectionRules/d/streams/s");
        assertEquals(3, uploads.size());
        for (Request upload : uploads) {
            assertEquals(1, json(upload.text()).size());
        }
    }

    @Test
    public void qradarHttpsPostsLeefLines() throws Exception {
        AuditSink sink = new QRadarSink(Fixtures.settings("qradar", "transport", "https", "url",
                server.url("/receiver")));
        sink.send(EVENTS);
        String[] lines = server.requests.get(0).text().split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0], lines[0].startsWith("LEEF:2.0|Maretha|"));
        assertTrue(lines[1], lines[1].contains("|documentModified|x09|"));
    }
}
