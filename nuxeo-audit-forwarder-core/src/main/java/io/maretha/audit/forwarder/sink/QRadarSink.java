/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.format.Cef;
import io.maretha.audit.forwarder.format.Leef;
import io.maretha.audit.forwarder.transport.HttpTransport;

/**
 * IBM QRadar. By default LEEF 2.0 behind an RFC 5424 header over syslog TLS with newline framing; alternatively
 * newline-separated events POSTed to an HTTP Receiver log source over HTTPS, where the client certificate
 * ({@code tls.keystore.path}) authenticates the sender.
 */
public class QRadarSink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new QRadarSink(settings);
        }
    }

    protected final AuditSink syslog;

    protected final URI uri;

    protected final String format;

    protected final Map<String, String> headers = new LinkedHashMap<>();

    protected final HttpTransport http;

    public QRadarSink(SinkSettings settings) {
        String transport = settings.choice("transport", "syslog", "syslog", "https");
        format = settings.choice("format", "leef", "leef", "cef");
        if ("syslog".equals(transport)) {
            syslog = new SyslogSink(settings, format, "tls", "newline");
            uri = null;
            http = null;
        } else {
            syslog = null;
            uri = settings.requireHttpUri("url");
            http = new HttpTransport(settings);
            headers.put("Content-Type", "text/plain; charset=utf-8");
            settings.withPrefix("headers.").forEach(headers::putIfAbsent);
            HttpTransport.validateHeaders(settings, uri, headers);
            HttpTransport.warnIfPlainHttp(settings, uri);
        }
    }

    @Override
    public String target() {
        return syslog != null ? syslog.target() : HttpTransport.describe(uri) + " (" + format + ")";
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        if (syslog != null) {
            syslog.send(events);
            return;
        }
        String body = events.stream()
                            .map(e -> "cef".equals(format) ? Cef.format(e) : Leef.format(e))
                            .collect(Collectors.joining("\n", "", "\n"));
        http.post(uri, headers, body.getBytes(UTF_8));
    }

    @Override
    public void close() {
        if (syslog != null) {
            syslog.close();
        }
    }
}
