/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import org.nuxeo.ecm.platform.audit.api.LogEntry;

/**
 * One audit log entry, normalized for every sink. Times are UTC instants; formatters render them as ISO-8601.
 *
 * @param id stable fingerprint of the entry, identical across sinks and redeliveries: use it to deduplicate
 */
public record AuditEvent(String id, String eventId, String category, Instant eventDate, Instant logDate,
        String principal, String comment, String docUUID, String docPath, String docType, String docLifeCycle,
        String repositoryId, Map<String, Object> extended, String sourceHost) {

    public AuditEvent {
        extended = extended == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(extended));
    }

    public static AuditEvent from(LogEntry entry, String sourceHost) {
        Instant eventDate = toInstant(entry.getEventDate());
        Instant logDate = toInstant(entry.getLogDate());
        Map<String, Object> extended = new LinkedHashMap<>();
        if (entry.getExtendedInfos() != null) {
            entry.getExtendedInfos().forEach((key, info) -> {
                Object value = info == null ? null : info.getSerializableValue();
                if (value != null) {
                    extended.put(key, value);
                }
            });
        }
        String id = fingerprint(entry.getEventId(), eventDate, logDate, entry.getPrincipalName(),
                entry.getDocUUID(), entry.getCategory(), entry.getComment(), entry.getRepositoryId());
        return new AuditEvent(id, entry.getEventId(), entry.getCategory(), eventDate, logDate,
                entry.getPrincipalName(), entry.getComment(), entry.getDocUUID(), entry.getDocPath(),
                entry.getDocType(), entry.getDocLifeCycle(), entry.getRepositoryId(), extended, sourceHost);
    }

    /** @return the event date, or the log date when the event carries none */
    public Instant time() {
        return eventDate != null ? eventDate : logDate != null ? logDate : Instant.EPOCH;
    }

    static String fingerprint(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(String.valueOf(part).getBytes(UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant toInstant(Date date) {
        return date == null ? null : date.toInstant();
    }
}
