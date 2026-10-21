/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.format;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.maretha.audit.forwarder.AuditEvent;

/** The canonical JSON shape of an event, shared by the JSON-based sinks. Null and empty fields are omitted. */
public final class EventJson {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private EventJson() {
    }

    public static Map<String, Object> toMap(AuditEvent event) {
        Map<String, Object> map = new LinkedHashMap<>();
        put(map, "id", event.id());
        put(map, "eventId", event.eventId());
        put(map, "category", event.category());
        put(map, "eventDate", iso(event.eventDate()));
        put(map, "logDate", iso(event.logDate()));
        put(map, "principal", event.principal());
        put(map, "comment", event.comment());
        put(map, "docUUID", event.docUUID());
        put(map, "docPath", event.docPath());
        put(map, "docType", event.docType());
        put(map, "docLifeCycle", event.docLifeCycle());
        put(map, "repositoryId", event.repositoryId());
        if (!event.extended().isEmpty()) {
            map.put("extended", safeValue(event.extended()));
        }
        put(map, "sourceHost", event.sourceHost());
        return map;
    }

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return ISO-8601 UTC with millisecond precision, or null */
    public static String iso(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    /** @return the value reduced to JSON-friendly types; unknown objects become their string form */
    public static Object safeValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Date date) {
            return iso(date.toInstant());
        }
        if (value instanceof Calendar calendar) {
            return iso(calendar.toInstant());
        }
        if (value instanceof Instant instant) {
            return iso(instant);
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            source.forEach((k, v) -> result.put(String.valueOf(k), safeValue(v)));
            return result;
        }
        if (value instanceof Collection<?> source) {
            List<Object> result = new ArrayList<>(source.size());
            source.forEach(v -> result.add(safeValue(v)));
            return result;
        }
        if (value instanceof Object[] source) {
            List<Object> result = new ArrayList<>(source.length);
            for (Object v : source) {
                result.add(safeValue(v));
            }
            return result;
        }
        return String.valueOf(value);
    }

    private static void put(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }
}
