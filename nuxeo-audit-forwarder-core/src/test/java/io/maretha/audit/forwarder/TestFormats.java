/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.maretha.audit.forwarder.format.Cef;
import io.maretha.audit.forwarder.format.EventJson;
import io.maretha.audit.forwarder.format.Leef;
import io.maretha.audit.forwarder.format.Product;
import io.maretha.audit.forwarder.format.Rfc5424;

public class TestFormats {

    @Test
    public void jsonUsesUtcIsoTimesAndOmitsEmptyFields() {
        AuditEvent event = new AuditEvent("id1", "loginSuccess", "eventCategory", Fixtures.TIME, null, "jdoe", null,
                null, null, null, null, null, Map.of(), "host");
        Map<String, Object> map = EventJson.toMap(event);
        assertEquals("2026-10-15T08:30:00.123Z", map.get("eventDate"));
        assertFalse(map.containsKey("logDate"));
        assertFalse(map.containsKey("docUUID"));
        assertFalse(map.containsKey("extended"));
        assertEquals("jdoe", map.get("principal"));
    }

    @Test
    public void jsonReducesExtendedValuesToSafeTypes() {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(Fixtures.TIME.toEpochMilli());
        Object safe = EventJson.safeValue(Map.of("when", calendar, "list", new String[] { "a", "b" }, "obj",
                new StringBuilder("x")));
        assertEquals(Map.of("when", "2026-10-15T08:30:00.123Z", "list", List.of("a", "b"), "obj", "x"), safe);
    }

    @Test
    public void cefHeaderAndExtensionsAreEscaped() {
        AuditEvent event = new AuditEvent("id1", "loginFailed", "cat", Fixtures.TIME, null, "j=doe",
                "line1\nline2 back\\slash", null, null, null, null, null, Map.of(), "host");
        String cef = Cef.format(event);
        assertTrue(cef, cef.startsWith("CEF:0|Maretha|Nuxeo Audit Forwarder|" + Product.VERSION
                + "|loginFailed|loginFailed|7|"));
        assertTrue(cef, cef.contains("rt=" + Fixtures.TIME.toEpochMilli()));
        assertTrue(cef, cef.contains("suser=j\\=doe"));
        assertTrue(cef, cef.contains("msg=line1\\nline2 back\\\\slash"));
        assertTrue(cef, cef.contains("externalId=id1"));
        assertFalse(cef, cef.contains("filePath="));
    }

    @Test
    public void cefEscapesPipesInHeader() {
        AuditEvent event = new AuditEvent("id1", "custom|event", null, Fixtures.TIME, null, null, null, null, null,
                null, null, null, Map.of(), null);
        assertTrue(Cef.format(event).contains("|custom\\|event|custom\\|event|3|"));
    }

    @Test
    public void leefIsTabDelimitedWithExplicitTimeFormat() {
        String leef = Leef.format(Fixtures.event("documentModified"));
        assertTrue(leef, leef.startsWith("LEEF:2.0|Maretha|Nuxeo Audit Forwarder|" + Product.VERSION
                + "|documentModified|x09|"));
        String attributes = leef.substring(leef.indexOf("|x09|") + 5);
        List<String> pairs = List.of(attributes.split("\t"));
        assertTrue(pairs.toString(), pairs.contains("devTime=2026-10-15T08:30:00.123+0000"));
        assertTrue(pairs.contains("devTimeFormat=yyyy-MM-dd'T'HH:mm:ss.SSSZ"));
        assertTrue(pairs.contains("usrName=jdoe"));
        assertTrue(pairs.contains("sev=3"));
        assertTrue(pairs.contains("resource=/default-domain/workspaces/ws/file"));
    }

    @Test
    public void leefReplacesTabsAndNewlinesInValues() {
        AuditEvent event = new AuditEvent("id1", "e", null, Fixtures.TIME, null, "a\tb", "x\ny", null, null, null,
                null, null, Map.of(), null);
        String leef = Leef.format(event);
        assertTrue(leef, leef.contains("usrName=a b\t"));
        assertTrue(leef, leef.contains("msg=x y"));
    }

    @Test
    public void rfc5424HeaderUsesFacilityAndSeverity() {
        String message = Rfc5424.message(Fixtures.event("loginFailed"), 13, "nuxeo audit", "BODY");
        // 13 * 8 + warning (4)
        assertEquals("<108>1 2026-10-15T08:30:00.123Z nuxeo-node-1 nuxeo_audit - loginFailed - BODY", message);
        String info = Rfc5424.message(Fixtures.event("documentModified"), 10, "app", "B");
        assertTrue(info, info.startsWith("<86>1 "));
    }

    @Test
    public void fingerprintIsStableAndSensitiveToContent() {
        Instant t = Fixtures.TIME;
        String a = AuditEvent.fingerprint("documentModified", t, t, "jdoe", "uuid");
        assertEquals(a, AuditEvent.fingerprint("documentModified", t, t, "jdoe", "uuid"));
        assertNotEquals(a, AuditEvent.fingerprint("documentModified", t, t, "jdoe", "uuid2"));
        assertEquals(32, a.length());
    }
}
