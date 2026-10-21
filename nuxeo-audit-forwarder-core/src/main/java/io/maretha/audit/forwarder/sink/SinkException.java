/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.sink;

/** A failed delivery. The message is shown to administrators, so it never contains credentials. */
public class SinkException extends Exception {

    private static final long serialVersionUID = 1L;

    public SinkException(String message) {
        super(message);
    }

    public SinkException(String message, Throwable cause) {
        super(message, cause);
    }
}
