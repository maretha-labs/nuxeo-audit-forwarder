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

@Operation(id = GetSettings.ID, category = Constants.CAT_SERVICES, label = "Audit Forwarder: get settings",
        description = "Returns the forwarded event selection. Administrators only.")
public class GetSettings {

    public static final String ID = "AuditForwarder.GetSettings";

    @Context
    protected CoreSession session;

    @Context
    protected AuditForwarderService service;

    @OperationMethod
    public Blob run() {
        AdminJson.requireAdministrator(session);
        return AdminJson.json(AdminJson.settings(service.getSettings(), service.isEnabled()));
    }
}
