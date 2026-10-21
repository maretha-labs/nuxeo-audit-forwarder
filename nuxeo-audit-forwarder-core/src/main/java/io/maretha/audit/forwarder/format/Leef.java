/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.format;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import io.maretha.audit.forwarder.AuditEvent;

/**
 * IBM QRadar Log Event Extended Format (LEEF) 2.0, tab-delimited. The event time is sent in {@code devTime} with an
 * explicit {@code devTimeFormat}. Tabs and line breaks inside values are replaced by spaces.
 */
public final class Leef {

    static final String DEV_TIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    static final DateTimeFormatter DEV_TIME = DateTimeFormatter.ofPattern(DEV_TIME_FORMAT).withZone(ZoneOffset.UTC);

    private Leef() {
    }

    public static String format(AuditEvent event) {
        String eventId = event.eventId() == null ? "unknown" : event.eventId();
        StringBuilder sb = new StringBuilder("LEEF:2.0|");
        sb.append(header(Product.VENDOR)).append('|');
        sb.append(header(Product.NAME)).append('|');
        sb.append(header(Product.VERSION)).append('|');
        sb.append(header(eventId)).append('|');
        sb.append("x09|");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("devTime", DEV_TIME.format(event.time()));
        attributes.put("devTimeFormat", DEV_TIME_FORMAT);
        attributes.put("cat", event.category());
        attributes.put("sev", String.valueOf(Severity.of(eventId).leef));
        attributes.put("usrName", event.principal());
        attributes.put("resource", event.docPath());
        attributes.put("docId", event.docUUID());
        attributes.put("docType", event.docType());
        attributes.put("docLifeCycle", event.docLifeCycle());
        attributes.put("repositoryId", event.repositoryId());
        attributes.put("msg", event.comment());
        attributes.put("auditId", event.id());
        attributes.put("sourceHost", event.sourceHost());
        String separator = "";
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                sb.append(separator).append(entry.getKey()).append('=').append(value(entry.getValue()));
                separator = "\t";
            }
        }
        return sb.toString();
    }

    static String header(String value) {
        return value.replace("|", "\\|").replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    static String value(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }
}
