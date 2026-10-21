/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import java.time.Instant;

/**
 * Delivery counters of one sink since the server started.
 *
 * @param dropped events discarded after the retries ran out, only with {@code continueOnFailure=true}
 * @param failing true while the latest attempt failed
 */
public record SinkStatus(String name, String type, String target, long sent, long failedAttempts, long dropped,
        Instant lastSuccessAt, String lastError, Instant lastErrorAt, boolean failing) {
}
