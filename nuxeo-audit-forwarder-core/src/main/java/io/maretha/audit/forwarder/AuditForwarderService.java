/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import java.util.List;

import org.nuxeo.ecm.core.api.CoreSession;

import io.maretha.audit.forwarder.settings.ForwarderSettings;
import io.maretha.audit.forwarder.sink.SinkStatus;
import io.maretha.audit.forwarder.sink.SinkTestResult;

/**
 * Forwards audit events to the sinks declared in nuxeo.conf. Each sink is fed by its own stream computation, so a sink
 * that is down never delays the others.
 */
public interface AuditForwarderService {

    boolean isEnabled();

    /** @return the names of the enabled sinks, in declaration order */
    List<String> getSinkNames();

    /** @return the host name stamped on every event */
    String getSourceHost();

    /**
     * Filters {@code events} for the sink and delivers them, retrying as its policy says. Called by the sink's stream
     * computation.
     *
     * @throws InterruptedException when the server stops while waiting to retry
     */
    void forward(String sinkName, List<AuditEvent> events) throws InterruptedException;

    List<SinkStatus> getSinkStatuses();

    /** Sends one synthetic event straight to the sink, bypassing filters, the stream and retries. */
    SinkTestResult testSink(String sinkName, String principal);

    /** @return the event selection, from the settings document or the nuxeo.conf default; cached briefly */
    ForwarderSettings getSettings();

    ForwarderSettings saveSettings(CoreSession session, List<String> events);
}
