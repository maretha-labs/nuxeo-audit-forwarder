/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import java.util.List;

import io.maretha.audit.forwarder.AuditEvent;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.format.Cef;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.format.Leef;
import io.maretha.audit.forwarder.format.Rfc5424;
import io.maretha.audit.forwarder.transport.SyslogTransport;

/**
 * RFC 5424 syslog over TLS (default), TCP or UDP. The message body is the event as JSON, CEF or LEEF. TLS can present
 * a client certificate; UDP and plain TCP are neither authenticated nor encrypted.
 */
public class SyslogSink implements AuditSink {

    public static class Factory implements AuditSinkFactory {
        @Override
        public AuditSink create(SinkSettings settings) {
            return new SyslogSink(settings, "json", "tls", "octet_counting");
        }
    }

    protected final String format;

    protected final int facility;

    protected final String appName;

    protected final SyslogTransport transport;

    public SyslogSink(SinkSettings settings, String defaultFormat, String defaultProtocol, String defaultFraming) {
        format = settings.choice("format", defaultFormat, "json", "cef", "leef");
        facility = settings.getInt("facility", 13);
        if (facility < 0 || facility > 23) {
            throw settings.invalid(settings.key("facility") + " must be between 0 and 23");
        }
        appName = settings.get("app.name", "nuxeo-audit");
        transport = SyslogTransport.create(settings, defaultProtocol, defaultFraming);
    }

    @Override
    public String target() {
        return transport.describe() + " (" + format + ")";
    }

    @Override
    public void send(List<AuditEvent> events) throws SinkException {
        transport.send(events.stream().map(e -> Rfc5424.message(e, facility, appName, body(e))).toList());
    }

    protected String body(AuditEvent event) {
        return switch (format) {
            case "cef" -> Cef.format(event);
            case "leef" -> Leef.format(event);
            default -> EventJson.toJson(EventJson.toMap(event));
        };
    }

    @Override
    public void close() {
        transport.close();
    }
}
