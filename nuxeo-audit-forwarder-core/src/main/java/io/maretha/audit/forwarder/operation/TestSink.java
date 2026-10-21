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
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;

import io.maretha.audit.forwarder.AuditForwarderService;

@Operation(id = TestSink.ID, category = Constants.CAT_SERVICES, label = "Audit Forwarder: test sink",
        description = "Sends one synthetic 'auditForwarderTest' event to the sink and reports the outcome."
                + " Administrators only.")
public class TestSink {

    public static final String ID = "AuditForwarder.TestSink";

    @Context
    protected CoreSession session;

    @Context
    protected AuditForwarderService service;

    @Param(name = "sink")
    protected String sink;

    @OperationMethod
    public Blob run() {
        AdminJson.requireAdministrator(session);
        return AdminJson.json(AdminJson.testResult(service.testSink(sink, session.getPrincipal().getName())));
    }
}
