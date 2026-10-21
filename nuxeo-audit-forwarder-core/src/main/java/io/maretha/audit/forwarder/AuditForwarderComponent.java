/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.repository.RepositoryManager;
import org.nuxeo.runtime.RuntimeMessage.Level;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentContext;
import org.nuxeo.runtime.model.DefaultComponent;
import org.nuxeo.runtime.model.Descriptor;
import org.nuxeo.runtime.transaction.TransactionHelper;

import io.maretha.audit.forwarder.config.ForwarderConfig;
import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.settings.ForwarderSettings;
import io.maretha.audit.forwarder.settings.SettingsStore;
import io.maretha.audit.forwarder.sink.AuditSink;
import io.maretha.audit.forwarder.sink.SinkRuntime;
import io.maretha.audit.forwarder.sink.SinkStatus;
import io.maretha.audit.forwarder.sink.SinkTestResult;

public class AuditForwarderComponent extends DefaultComponent implements AuditForwarderService {

    private static final Logger log = LogManager.getLogger(AuditForwarderComponent.class);

    public static final String XP_CONFIGURATION = "configuration";

    public static final String XP_SINK_TYPES = "sinkTypes";

    protected record State(ForwarderConfig config, Map<String, SinkRuntime> sinks) {
    }

    protected record CachedSettings(ForwarderSettings settings, Instant expiresAt) {
    }

    protected final SettingsStore store = new SettingsStore();

    protected volatile State state;

    protected volatile CachedSettings cachedSettings;

    /** Before the stream service (-590), whose topologies ask for the sink list. */
    @Override
    public int getApplicationStartedOrder() {
        return -600;
    }

    @Override
    public void start(ComponentContext context) {
        state();
    }

    @Override
    public void stop(ComponentContext context) throws InterruptedException {
        State current = state;
        state = null;
        cachedSettings = null;
        if (current != null) {
            current.sinks().values().forEach(SinkRuntime::close);
        }
    }

    protected State state() {
        State current = state;
        if (current == null) {
            synchronized (this) {
                current = state;
                if (current == null) {
                    current = buildState();
                    state = current;
                }
            }
        }
        return current;
    }

    protected State buildState() {
        AuditForwarderDescriptor desc = getDescriptor(XP_CONFIGURATION, Descriptor.UNIQUE_DESCRIPTOR_ID);
        if (desc == null) {
            throw new AuditForwarderException("No audit forwarder configuration contributed");
        }
        try {
            ForwarderConfig config = ForwarderConfig.build(desc.enabled, desc.sinks, desc.defaultEvents,
                    desc.excludePrincipals, desc.sourceHost, desc.settingsCacheSeconds, desc.sinkPropertyPrefix,
                    Framework.getProperties());
            if (!config.enabled()) {
                log.info("Audit forwarder disabled (maretha.audit.forwarder.enabled=false)");
                return new State(config, Map.of());
            }
            List<String> declared = config.sinks().stream().map(SinkSettings::name).toList();
            Set<String> undeclared = ForwarderConfig.undeclaredSinks(Framework.getProperties(),
                    desc.sinkPropertyPrefix, declared);
            if (!undeclared.isEmpty()) {
                log.warn("Audit forwarder: settings found for sinks missing from maretha.audit.forwarder.sinks: {}",
                        undeclared);
            }
            return new State(config, createSinks(config));
        } catch (AuditForwarderException e) {
            addRuntimeMessage(Level.ERROR, e.getMessage());
            throw e;
        }
    }

    protected Map<String, SinkRuntime> createSinks(ForwarderConfig config) {
        Map<String, SinkTypeDescriptor> types = new TreeMap<>();
        for (SinkTypeDescriptor type : this.<SinkTypeDescriptor> getDescriptors(XP_SINK_TYPES)) {
            types.put(type.getId(), type);
        }
        Map<String, SinkRuntime> sinks = new LinkedHashMap<>();
        try {
            for (SinkSettings settings : config.sinks()) {
                if (!settings.enabled()) {
                    log.info("Audit forwarder sink '{}' is disabled", settings.name());
                    continue;
                }
                SinkTypeDescriptor type = types.get(settings.type());
                if (type == null) {
                    throw settings.invalid(settings.key("type") + " must be one of " + types.keySet());
                }
                AuditSink sink = type.newFactory().create(settings);
                sinks.put(settings.name(), new SinkRuntime(settings, sink, config.sourceHost()));
                log.info("Audit forwarder sink '{}' ({}) sends to {}", settings.name(), settings.type(),
                        sink.target());
            }
        } catch (RuntimeException e) {
            sinks.values().forEach(SinkRuntime::close);
            throw e;
        }
        return sinks;
    }

    @Override
    public boolean isEnabled() {
        State current = state();
        return current.config().enabled() && !current.sinks().isEmpty();
    }

    @Override
    public List<String> getSinkNames() {
        return List.copyOf(state().sinks().keySet());
    }

    @Override
    public String getSourceHost() {
        return state().config().sourceHost();
    }

    @Override
    public void forward(String sinkName, List<AuditEvent> events) throws InterruptedException {
        State current = state();
        SinkRuntime sink = current.sinks().get(sinkName);
        if (sink == null) {
            log.warn("Audit forwarder: no sink named '{}', skipping {} events", sinkName, events.size());
            return;
        }
        ForwarderSettings settings = getSettings();
        Set<String> excluded = current.config().excludedPrincipals();
        List<AuditEvent> accepted = new ArrayList<>(events.size());
        for (AuditEvent event : events) {
            if (settings.selects(event.eventId()) && sink.accepts(event)
                    && (event.principal() == null || !excluded.contains(event.principal()))) {
                accepted.add(event);
            }
        }
        if (!accepted.isEmpty()) {
            sink.deliver(accepted);
        }
    }

    @Override
    public List<SinkStatus> getSinkStatuses() {
        return state().sinks().values().stream().map(SinkRuntime::status).toList();
    }

    @Override
    public SinkTestResult testSink(String sinkName, String principal) {
        SinkRuntime sink = state().sinks().get(sinkName);
        if (sink == null) {
            throw new AuditForwarderException("No enabled sink named '" + sinkName + "'", 404);
        }
        return sink.test(principal);
    }

    @Override
    public ForwarderSettings getSettings() {
        CachedSettings cached = cachedSettings;
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.settings();
        }
        ForwarderConfig config = state().config();
        ForwarderSettings settings;
        try {
            settings = TransactionHelper.isTransactionActive() ? loadSettings(config)
                    : TransactionHelper.runInTransaction(() -> loadSettings(config));
        } catch (RuntimeException e) {
            settings = cached != null ? cached.settings()
                    : new ForwarderSettings(config.defaultEvents(), false, null, null);
            log.warn("Audit forwarder: cannot read the settings document, using {}: {}",
                    cached != null ? "the previous selection" : "the nuxeo.conf default", e.toString());
        }
        cachedSettings = new CachedSettings(settings, Instant.now().plus(config.settingsCacheTtl()));
        return settings;
    }

    protected ForwarderSettings loadSettings(ForwarderConfig config) {
        return CoreInstance.doPrivileged(defaultRepository(),
                (CoreSession session) -> store.load(session, config.defaultEvents()));
    }

    @Override
    public ForwarderSettings saveSettings(CoreSession session, List<String> events) {
        List<String> normalized = events.stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        String user = session.getPrincipal().getName();
        ForwarderSettings saved = CoreInstance.doPrivileged(defaultRepository(),
                (CoreSession privileged) -> store.save(privileged, normalized, user));
        cachedSettings = new CachedSettings(saved, Instant.now().plus(state().config().settingsCacheTtl()));
        log.info("Audit forwarder: event selection changed by {} ({} events)", user, normalized.size());
        return saved;
    }

    protected String defaultRepository() {
        return Framework.getService(RepositoryManager.class).getDefaultRepositoryName();
    }
}
