/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

/** Outcome of sending one synthetic event to a sink. */
public record SinkTestResult(String sink, boolean ok, String message, long durationMs) {
}
