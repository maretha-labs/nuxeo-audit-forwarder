/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

import io.maretha.audit.forwarder.config.SinkSettings;

/**
 * Builds a sink from its nuxeo.conf settings. Contributed to the {@code sinkTypes} extension point; validates every
 * setting it needs and fails with a message naming the key.
 */
@FunctionalInterface
public interface AuditSinkFactory {

    AuditSink create(SinkSettings settings);
}
