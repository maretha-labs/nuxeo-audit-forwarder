/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.format;

import java.util.LinkedHashMap;
import java.util.Map;

import io.maretha.audit.forwarder.AuditEvent;

/**
 * ArcSight Common Event Format (CEF) version 0. Header fields escape {@code \} and {@code |}; extension values escape
 * {@code \} and {@code =} and encode line breaks as {@code \n} / {@code \r}.
 */
public final class Cef {

    private Cef() {
    }

    public static String format(AuditEvent event) {
        String eventId = event.eventId() == null ? "unknown" : event.eventId();
        StringBuilder sb = new StringBuilder("CEF:0|");
        sb.append(header(Product.VENDOR)).append('|');
        sb.append(header(Product.NAME)).append('|');
        sb.append(header(Product.VERSION)).append('|');
        sb.append(header(eventId)).append('|');
        sb.append(header(eventId)).append('|');
        sb.append(Severity.of(eventId).cef).append('|');
        Map<String, String> ext = new LinkedHashMap<>();
        ext.put("rt", String.valueOf(event.time().toEpochMilli()));
        ext.put("act", eventId);
        ext.put("cat", event.category());
        ext.put("suser", event.principal());
        ext.put("msg", event.comment());
        ext.put("fileId", event.docUUID());
        ext.put("filePath", event.docPath());
        ext.put("fileType", event.docType());
        if (event.docLifeCycle() != null) {
            ext.put("cs1Label", "docLifeCycle");
            ext.put("cs1", event.docLifeCycle());
        }
        if (event.repositoryId() != null) {
            ext.put("cs2Label", "repositoryId");
            ext.put("cs2", event.repositoryId());
        }
        ext.put("externalId", event.id());
        ext.put("dvchost", event.sourceHost());
        String separator = "";
        for (Map.Entry<String, String> entry : ext.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                sb.append(separator).append(entry.getKey()).append('=').append(extension(entry.getValue()));
                separator = " ";
            }
        }
        return sb.toString();
    }

    static String header(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|").replace('\r', ' ').replace('\n', ' ');
    }

    static String extension(String value) {
        return value.replace("\\", "\\\\").replace("=", "\\=").replace("\r", "\\r").replace("\n", "\\n");
    }
}
