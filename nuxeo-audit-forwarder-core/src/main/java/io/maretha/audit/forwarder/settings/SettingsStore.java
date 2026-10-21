/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.settings;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.api.security.impl.ACPImpl;

/**
 * Persists the event selection in one hidden document under the repository root, readable and writable by
 * administrators only. It holds no credentials: those stay in nuxeo.conf.
 */
public class SettingsStore {

    public static final String DOC_TYPE = "AuditForwarderSettings";

    public static final String DOC_NAME = "audit-forwarder-settings";

    public static final String EVENTS = "afwd:events";

    public static final String UPDATED_AT = "afwd:updatedAt";

    public static final String UPDATED_BY = "afwd:updatedBy";

    protected static final PathRef REF = new PathRef("/" + DOC_NAME);

    /** @param session a privileged session */
    public ForwarderSettings load(CoreSession session, List<String> defaults) {
        if (!session.exists(REF)) {
            return new ForwarderSettings(defaults, false, null, null);
        }
        DocumentModel doc = session.getDocument(REF);
        String[] events = (String[]) doc.getPropertyValue(EVENTS);
        Calendar updatedAt = (Calendar) doc.getPropertyValue(UPDATED_AT);
        return new ForwarderSettings(events == null ? List.of() : Arrays.asList(events), true,
                updatedAt == null ? null : updatedAt.toInstant(), (String) doc.getPropertyValue(UPDATED_BY));
    }

    /** @param session a privileged session; the caller has already checked that the user is an administrator */
    public ForwarderSettings save(CoreSession session, List<String> events, String user) {
        DocumentModel doc;
        if (session.exists(REF)) {
            doc = session.getDocument(REF);
        } else {
            doc = session.createDocument(session.createDocumentModel("/", DOC_NAME, DOC_TYPE));
            ACP acp = new ACPImpl();
            ACL acl = acp.getOrCreateACL(ACL.LOCAL_ACL);
            acl.add(new ACE(SecurityConstants.ADMINISTRATORS, SecurityConstants.EVERYTHING, true));
            acl.add(ACE.BLOCK);
            session.setACP(doc.getRef(), acp, true);
        }
        Calendar now = Calendar.getInstance();
        doc.setPropertyValue(EVENTS, (Serializable) events.toArray(String[]::new));
        doc.setPropertyValue(UPDATED_AT, now);
        doc.setPropertyValue(UPDATED_BY, user);
        session.saveDocument(doc);
        session.save();
        return new ForwarderSettings(events, true, Instant.ofEpochMilli(now.getTimeInMillis()), user);
    }
}
