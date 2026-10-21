/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.format;

import java.util.Set;

/** Severity of an audit event on the syslog (0-7, lower is worse), CEF (0-10) and LEEF (1-10) scales. */
public enum Severity {

    INFO(6, 3, 3), NOTICE(5, 5, 5), WARNING(4, 7, 7);

    static final Set<String> WARNING_EVENTS = Set.of("loginFailed");

    static final Set<String> NOTICE_EVENTS = Set.of("documentSecurityUpdated", "documentRemoved", "user_created",
            "user_deleted", "user_modified", "group_created", "group_deleted", "group_modified", "afterSetLegalHold",
            "afterRemoveLegalHold", "afterSetRetention", "retentionActiveChanged");

    public final int syslog;

    public final int cef;

    public final int leef;

    Severity(int syslog, int cef, int leef) {
        this.syslog = syslog;
        this.cef = cef;
        this.leef = leef;
    }

    public static Severity of(String eventId) {
        if (WARNING_EVENTS.contains(eventId)) {
            return WARNING;
        }
        return NOTICE_EVENTS.contains(eventId) ? NOTICE : INFO;
    }
}
