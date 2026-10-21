/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.format;

import io.maretha.audit.forwarder.AuditEvent;

/**
 * RFC 5424 syslog message: {@code <PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID SD MSG}. Header fields are
 * restricted to printable US-ASCII; the message body is UTF-8 without a byte order mark.
 */
public final class Rfc5424 {

    private Rfc5424() {
    }

    public static String message(AuditEvent event, int facility, String appName, String body) {
        int priority = facility * 8 + Severity.of(event.eventId()).syslog;
        return "<" + priority + ">1 " + EventJson.iso(event.time()) + ' ' + field(event.sourceHost(), 255) + ' '
                + field(appName, 48) + " - " + field(event.eventId(), 32) + " - " + body;
    }

    /** @return the value limited to printable US-ASCII without spaces, truncated, or {@code -} when empty */
    static String field(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder(Math.min(value.length(), maxLength));
        for (int i = 0; i < value.length() && sb.length() < maxLength; i++) {
            char c = value.charAt(i);
            sb.append(c >= 33 && c <= 126 ? c : '_');
        }
        return sb.toString();
    }
}
