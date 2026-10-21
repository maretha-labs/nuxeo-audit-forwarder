/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.operation;

import java.util.TreeSet;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.platform.audit.api.AuditLogger;

@Operation(id = ListAuditableEvents.ID, category = Constants.CAT_SERVICES,
        label = "Audit Forwarder: list auditable events",
        description = "Returns the event names Nuxeo audits, sorted. Administrators only.")
public class ListAuditableEvents {

    public static final String ID = "AuditForwarder.ListAuditableEvents";

    @Context
    protected CoreSession session;

    @Context
    protected AuditLogger auditService;

    @OperationMethod
    public Blob run() {
        AdminJson.requireAdministrator(session);
        return AdminJson.json(new TreeSet<>(auditService.getAuditableEventNames()));
    }
}
