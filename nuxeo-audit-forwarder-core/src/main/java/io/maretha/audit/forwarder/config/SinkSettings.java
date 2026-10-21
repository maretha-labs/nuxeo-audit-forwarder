/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

import io.maretha.audit.forwarder.AuditForwarderException;

/**
 * The nuxeo.conf settings of one sink, keyed without the {@code maretha.audit.forwarder.sink.<name>.} prefix. Error
 * messages name the full key, never the value.
 */
public final class SinkSettings {

    private final String name;

    private final String keyPrefix;

    private final Map<String, String> values;

    public SinkSettings(String name, String keyPrefix, Map<String, String> values) {
        this.name = name;
        this.keyPrefix = keyPrefix;
        this.values = Map.copyOf(values);
    }

    public String name() {
        return name;
    }

    public String type() {
        return StringUtils.lowerCase(get("type"), Locale.ROOT);
    }

    /** @return the full nuxeo.conf key of {@code key} */
    public String key(String key) {
        return keyPrefix + key;
    }

    /** @return the trimmed value, or null when unset or blank */
    public String get(String key) {
        return StringUtils.trimToNull(values.get(key));
    }

    public String get(String key, String defaultValue) {
        String value = get(key);
        return value == null ? defaultValue : value;
    }

    public String require(String key) {
        String value = get(key);
        if (value == null) {
            throw invalid("missing required setting " + key(key));
        }
        return value;
    }

    public int getInt(String key, int defaultValue) {
        return (int) getLong(key, defaultValue);
    }

    public long getLong(String key, long defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw invalid(key(key) + " must be a whole number");
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null) {
            return defaultValue;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalid(key(key) + " must be true or false");
        };
    }

    public Duration getMillis(String key, long defaultMillis) {
        return Duration.ofMillis(getLong(key, defaultMillis));
    }

    /** @return the lower-cased value, which must be one of {@code allowed} */
    public String choice(String key, String defaultValue, String... allowed) {
        String value = StringUtils.lowerCase(get(key, defaultValue), Locale.ROOT);
        if (value == null) {
            throw invalid("missing required setting " + key(key) + ", one of " + Arrays.toString(allowed));
        }
        if (!List.of(allowed).contains(value)) {
            throw invalid(key(key) + " must be one of " + Arrays.toString(allowed));
        }
        return value;
    }

    /** @return the required http or https URI under {@code key} */
    public URI requireHttpUri(String key) {
        String value = require(key);
        try {
            URI uri = new URI(value);
            String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
            if (!"https".equals(scheme) && !"http".equals(scheme) || uri.getHost() == null) {
                throw invalid(key(key) + " must be an absolute http or https URL");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw invalid(key(key) + " is not a valid URL");
        }
    }

    /** @return the entries whose key starts with {@code prefix}, keyed without it */
    public Map<String, String> withPrefix(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        values.keySet().stream().sorted().forEach(key -> {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                result.put(key.substring(prefix.length()), StringUtils.trim(values.get(key)));
            }
        });
        return result;
    }

    // settings shared by every sink

    public boolean enabled() {
        return getBoolean("enabled", true);
    }

    /** @return the events this sink accepts on top of the global selection; empty means all selected events */
    public Set<String> events() {
        return new LinkedHashSet<>(ForwarderConfig.csv(get("events")));
    }

    public int batchSize() {
        int size = getInt("batch.size", 100);
        if (size < 1) {
            throw invalid(key("batch.size") + " must be at least 1");
        }
        return size;
    }

    /** @return true to drop a batch after {@link #maxAttempts()} failures instead of retrying it forever */
    public boolean continueOnFailure() {
        return getBoolean("continueOnFailure", false);
    }

    public int maxAttempts() {
        return Math.max(1, getInt("retry.maxAttempts", 5));
    }

    public Duration initialDelay() {
        return getMillis("retry.initialDelay.ms", 1000);
    }

    public Duration maxDelay() {
        return getMillis("retry.maxDelay.ms", 60000);
    }

    public AuditForwarderException invalid(String message) {
        return new AuditForwarderException("Audit forwarder sink '" + name + "': " + message);
    }
}
