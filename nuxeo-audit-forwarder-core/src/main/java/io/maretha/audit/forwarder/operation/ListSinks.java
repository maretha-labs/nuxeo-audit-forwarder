/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.operation;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;

import io.maretha.audit.forwarder.AuditForwarderService;

@Operation(id = ListSinks.ID, category = Constants.CAT_SERVICES, label = "Audit Forwarder: list sinks",
        description = "Returns each enabled sink with its target and delivery counters. Administrators only.")
public class ListSinks {

    public static final String ID = "AuditForwarder.ListSinks";

    @Context
    protected CoreSession session;

    @Context
    protected AuditForwarderService service;

    @OperationMethod
    public Blob run() {
        AdminJson.requireAdministrator(session);
        return AdminJson.json(service.getSinkStatuses().stream().map(AdminJson::status).toList());
    }
}
