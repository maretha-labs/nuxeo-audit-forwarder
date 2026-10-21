/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.transport;

import io.maretha.audit.forwarder.sink.SinkException;

/** The receiver answered with a non-2xx status. */
public class HttpStatusException extends SinkException {

    private static final long serialVersionUID = 1L;

    protected final int status;

    public HttpStatusException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
