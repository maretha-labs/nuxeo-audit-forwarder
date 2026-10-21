/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import org.nuxeo.ecm.core.api.NuxeoException;

/** Configuration or runtime failure of the forwarder. Messages never contain secret values. */
public class AuditForwarderException extends NuxeoException {

    private static final long serialVersionUID = 1L;

    public AuditForwarderException(String message) {
        super(message);
    }

    public AuditForwarderException(String message, int statusCode) {
        super(message, statusCode);
    }

    public AuditForwarderException(String message, Throwable cause) {
        super(message, cause);
    }
}
