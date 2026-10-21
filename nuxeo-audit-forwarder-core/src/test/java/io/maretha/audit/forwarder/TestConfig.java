/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.junit.Test;

import io.maretha.audit.forwarder.config.ForwarderConfig;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.sink.SplunkHecSink;
import io.maretha.audit.forwarder.sink.SyslogSink;
import io.maretha.audit.forwarder.sink.WebhookSink;

public class TestConfig {

    static final String PREFIX = "maretha.audit.forwarder.sink.";

    static ForwarderConfig build(boolean enabled, String sinks, Properties properties) {
        return ForwarderConfig.build(enabled, sinks, "a,b", "system", "host1", 30, PREFIX, properties);
    }

    @Test
    public void disabledConfigurationIsNotValidated() {
        ForwarderConfig config = build(false, "", new Properties());
        assertFalse(config.enabled());
        assertTrue(config.sinks().isEmpty());
    }

    @Test
    public void enabledWithoutSinksFails() {
        AuditForwarderException e = assertThrows(AuditForwarderException.class,
                () -> build(true, " ", new Properties()));
        assertTrue(e.getMessage(), e.getMessage().contains("maretha.audit.forwarder.sinks"));
    }

    @Test
    public void reportsEveryProblemAtOnce() {
        Properties properties = new Properties();
        properties.setProperty(PREFIX + "ok.type", "splunk");
        AuditForwarderException e = assertThrows(AuditForwarderException.class,
                () -> build(true, "ok,bad.name,notype,ok", properties));
        assertTrue(e.getMessage(), e.getMessage().contains("invalid sink name 'bad.name'"));
        assertTrue(e.getMessage(), e.getMessage().contains(PREFIX + "notype.type"));
    }

    @Test
    public void sinkSettingsAreScopedToTheirPrefix() {
        Properties properties = new Properties();
        properties.setProperty(PREFIX + "s1.type", "Splunk");
        properties.setProperty(PREFIX + "s1.url", " https://example.invalid ");
        properties.setProperty(PREFIX + "s1.headers.X-A", "1");
        properties.setProperty(PREFIX + "s10.type", "syslog");
        ForwarderConfig config = build(true, "s1,s10", properties);
        SinkSettings s1 = config.sinks().get(0);
        assertEquals("splunk", s1.type());
        assertEquals("https://example.invalid", s1.get("url"));
        assertEquals(java.util.Map.of("X-A", "1"), s1.withPrefix("headers."));
        assertEquals(List.of("a", "b"), config.defaultEvents());
        assertEquals(Set.of("system"), config.excludedPrincipals());
        assertEquals("host1", config.sourceHost());
    }

    @Test
    public void detectsSettingsOfUndeclaredSinks() {
        Properties properties = new Properties();
        properties.setProperty(PREFIX + "splunk.type", "splunk");
        properties.setProperty(PREFIX + "splnk.url", "x");
        assertEquals(Set.of("splnk"), ForwarderConfig.undeclaredSinks(properties, PREFIX, List.of("splunk")));
    }

    @Test
    public void missingRequiredSettingNamesTheKeyNotTheValue() {
        SinkSettings settings = Fixtures.settings("hec", "type", "splunk", "url", "https://splunk.example.invalid",
                "channel", "secret-channel-value");
        AuditForwarderException e = assertThrows(AuditForwarderException.class, () -> new SplunkHecSink(settings));
        assertTrue(e.getMessage(), e.getMessage().contains(PREFIX + "hec.token"));
        assertFalse(e.getMessage(), e.getMessage().contains("secret-channel-value"));
    }

    @Test
    public void webhookAuthenticationMustBeDeclared() {
        AuditForwarderException e = assertThrows(AuditForwarderException.class,
                () -> new WebhookSink(Fixtures.settings("hook", "url", "https://hook.example.invalid")));
        assertTrue(e.getMessage(), e.getMessage().contains(PREFIX + "hook.auth.type"));
        // explicit opt-out is accepted
        new WebhookSink(Fixtures.settings("hook", "url", "https://hook.example.invalid", "auth.type", "none"));
    }

    @Test
    public void rejectsBadValues() {
        assertThrows(AuditForwarderException.class, () -> new SyslogSink(
                Fixtures.settings("s", "host", "h", "protocol", "smtp"), "json", "tls", "octet_counting"));
        assertThrows(AuditForwarderException.class, () -> new SyslogSink(
                Fixtures.settings("s", "host", "h", "facility", "24"), "json", "tls", "octet_counting"));
        assertThrows(AuditForwarderException.class,
                () -> new SplunkHecSink(Fixtures.settings("s", "url", "ftp://x", "token", "t")));
        assertThrows(AuditForwarderException.class,
                () -> Fixtures.settings("s", "batch.size", "zero").batchSize());
    }
}
