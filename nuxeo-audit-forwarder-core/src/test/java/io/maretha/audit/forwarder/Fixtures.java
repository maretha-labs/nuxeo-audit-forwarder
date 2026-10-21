/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import io.maretha.audit.forwarder.config.SinkSettings;

public final class Fixtures {

    public static final Instant TIME = Instant.parse("2026-10-15T08:30:00.123Z");

    private Fixtures() {
    }

    public static AuditEvent event(String eventId) {
        return new AuditEvent("0123456789abcdef0123456789abcdef", eventId, "eventDocumentCategory", TIME, TIME,
                "jdoe", "a comment", "doc-uuid-1", "/default-domain/workspaces/ws/file", "File", "project",
                "default", Map.of("reason", "test"), "nuxeo-node-1");
    }

    public static SinkSettings settings(String name, String... keyValues) {
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            values.put(keyValues[i], keyValues[i + 1]);
        }
        return new SinkSettings(name, "maretha.audit.forwarder.sink." + name + ".", values);
    }
}
