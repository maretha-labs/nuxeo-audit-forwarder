/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.transport.HttpTransport;

/**
 * Splunk HTTP Event Collector, JSON event endpoint ({@code /services/collector/event}). Events are batched as
 * concatenated JSON objects; {@code time}, {@code host}, {@code source}, {@code sourcetype} and {@code index} are HEC
 * metadata, set beside the event, not inside it.
 */
public class SplunkHecSink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new SplunkHecSink(settings);
        }
    }

    protected final URI uri;

    protected final String token;

    protected final String channel;

    protected final String index;

    protected final String source;

    protected final String sourcetype;

    protected final String host;

    protected final HttpTransport http;

    public SplunkHecSink(SinkSettings settings) {
        uri = settings.requireHttpUri("url");
        token = settings.require("token");
        channel = settings.get("channel");
        index = settings.get("index");
        source = settings.get("source", "nuxeo:audit");
        sourcetype = settings.get("sourcetype", "_json");
        host = settings.get("host");
        http = new HttpTransport(settings);
        HttpTransport.warnIfPlainHttp(settings, uri);
    }

    @Override
    public String target() {
        return HttpTransport.describe(uri);
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        String body = events.stream().map(this::envelope).map(EventJson::toJson).collect(Collectors.joining("\n"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Splunk " + token);
        headers.put("Content-Type", "application/json");
        if (channel != null) {
            headers.put("X-Splunk-Request-Channel", channel);
        }
        http.post(uri, headers, body.getBytes(UTF_8));
    }

    protected Map<String, Object> envelope(AuditEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        // seconds since the epoch, millisecond precision
        envelope.put("time", BigDecimal.valueOf(event.time().toEpochMilli(), 3));
        String eventHost = host != null ? host : event.sourceHost();
        if (eventHost != null) {
            envelope.put("host", eventHost);
        }
        envelope.put("source", source);
        envelope.put("sourcetype", sourcetype);
        if (index != null) {
            envelope.put("index", index);
        }
        envelope.put("event", EventJson.toMap(event));
        return envelope;
    }
}
