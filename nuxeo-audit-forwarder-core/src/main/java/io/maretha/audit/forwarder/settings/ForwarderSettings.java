/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.settings;

import java.time.Instant;
import java.util.List;

/**
 * The event selection editable from the administration page.
 *
 * @param events event names to forward; {@code *} forwards every audited event
 * @param fromDocument false while nobody has saved a selection and the nuxeo.conf default applies
 */
public record ForwarderSettings(List<String> events, boolean fromDocument, Instant updatedAt, String updatedBy) {

    public static final String ALL_EVENTS = "*";

    public ForwarderSettings {
        events = List.copyOf(events);
    }

    public boolean selects(String eventId) {
        return events.contains(ALL_EVENTS) || events.contains(eventId);
    }
}
