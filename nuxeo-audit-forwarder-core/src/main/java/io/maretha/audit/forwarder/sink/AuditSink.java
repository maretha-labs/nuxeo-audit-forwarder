/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import java.util.List;

import io.maretha.audit.forwarder.AuditEvent;

/** A destination for audit events. Implementations are called from one thread at a time. */
public interface AuditSink extends AutoCloseable {

    /** @return where the events go, for display: never includes credentials */
    String target();

    /**
     * Delivers all {@code events} or throws. A batch that throws is retried as a whole, so receivers may see
     * duplicates: {@link AuditEvent#id()} is stable for deduplication.
     */
    void send(List<AuditEvent> events) throws SinkException;

    @Override
    default void close() {
    }
}
