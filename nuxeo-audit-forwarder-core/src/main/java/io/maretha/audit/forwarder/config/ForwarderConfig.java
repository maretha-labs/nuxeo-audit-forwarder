/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import io.maretha.audit.forwarder.AuditForwarderException;

/**
 * Immutable forwarder configuration built from the descriptor and the nuxeo.conf properties. Sink-specific keys are
 * validated later by each sink factory.
 */
public record ForwarderConfig(boolean enabled, List<SinkSettings> sinks, List<String> defaultEvents,
        Set<String> excludedPrincipals, String sourceHost, Duration settingsCacheTtl) {

    /** Sink names become stream computation names, so they are kept simple. */
    static final Pattern SINK_NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public static ForwarderConfig build(boolean enabled, String sinkList, String defaultEvents,
            String excludePrincipals, String sourceHost, long settingsCacheSeconds, String sinkPropertyPrefix,
            Properties properties) {
        List<String> errors = new ArrayList<>();
        if (StringUtils.isBlank(sinkPropertyPrefix)) {
            throw new AuditForwarderException("The sink property prefix is missing from the forwarder descriptor");
        }
        List<SinkSettings> sinks = new ArrayList<>();
        if (enabled) {
            List<String> names = csv(sinkList);
            if (names.isEmpty()) {
                errors.add("no sink declared: set maretha.audit.forwarder.sinks=<name>[,<name>...]");
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String name : names) {
                if (!SINK_NAME.matcher(name).matches()) {
                    errors.add("invalid sink name '" + name + "': use letters, digits, '-' or '_'");
                    continue;
                }
                if (!seen.add(name)) {
                    errors.add("sink '" + name + "' is declared twice");
                    continue;
                }
                String keyPrefix = sinkPropertyPrefix + name + ".";
                SinkSettings settings = new SinkSettings(name, keyPrefix, withPrefix(properties, keyPrefix));
                if (settings.type() == null) {
                    errors.add("missing required setting " + keyPrefix + "type");
                    continue;
                }
                sinks.add(settings);
            }
        }
        if (!errors.isEmpty()) {
            throw new AuditForwarderException("Invalid audit forwarder configuration: " + String.join("; ", errors));
        }
        return new ForwarderConfig(enabled, List.copyOf(sinks), csv(defaultEvents),
                new LinkedHashSet<>(csv(excludePrincipals)),
                StringUtils.isBlank(sourceHost) ? localHostName() : sourceHost.trim(),
                Duration.ofSeconds(Math.max(0, settingsCacheSeconds)));
    }

    /** @return sink names that have settings in nuxeo.conf but are missing from the sinks list: likely a typo */
    public static Set<String> undeclaredSinks(Properties properties, String sinkPropertyPrefix, List<String> declared) {
        Set<String> result = new LinkedHashSet<>();
        for (String key : withPrefix(properties, sinkPropertyPrefix).keySet()) {
            int dot = key.indexOf('.');
            if (dot > 0 && !declared.contains(key.substring(0, dot))) {
                result.add(key.substring(0, dot));
            }
        }
        return result;
    }

    /**
     * @return the properties whose key starts with {@code prefix}, keyed without the prefix, values trimmed and
     *         decrypted by the runtime properties
     */
    public static Map<String, String> withPrefix(Properties properties, String prefix) {
        Map<String, String> result = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                result.put(key.substring(prefix.length()), StringUtils.trim(properties.getProperty(key)));
            }
        }
        return new LinkedHashMap<>(result);
    }

    public static List<String> csv(String value) {
        if (StringUtils.isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private static String localHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "nuxeo";
        }
    }
}
