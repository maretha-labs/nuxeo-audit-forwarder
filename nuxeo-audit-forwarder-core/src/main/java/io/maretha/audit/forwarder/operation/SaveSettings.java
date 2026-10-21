/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.operation;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.util.StringList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;

import io.maretha.audit.forwarder.AuditForwarderService;

@Operation(id = SaveSettings.ID, category = Constants.CAT_SERVICES, label = "Audit Forwarder: save settings",
        description = "Replaces the forwarded event selection; '*' selects every audited event. Administrators only.")
public class SaveSettings {

    public static final String ID = "AuditForwarder.SaveSettings";

    @Context
    protected CoreSession session;

    @Context
    protected AuditForwarderService service;

    @Param(name = "events", required = false)
    protected StringList events = new StringList();

    @OperationMethod
    public Blob run() {
        AdminJson.requireAdministrator(session);
        return AdminJson.json(AdminJson.settings(service.saveSettings(session, events), service.isEnabled()));
    }
}
