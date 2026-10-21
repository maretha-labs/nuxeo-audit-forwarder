/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.transport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

import io.maretha.audit.forwarder.config.SinkSettings;

/**
 * Builds the TLS context of a sink from its {@code tls.truststore.*} (private CA) and {@code tls.keystore.*} (client
 * certificate for mutual TLS) settings. Certificate and host name verification are always on.
 */
public final class TlsSupport {

    private TlsSupport() {
    }

    /** @return the sink's TLS context, or null when it sets neither store and the JVM default applies */
    public static SSLContext sslContext(SinkSettings settings) {
        String truststore = settings.get("tls.truststore.path");
        String keystore = settings.get("tls.keystore.path");
        if (truststore == null && keystore == null) {
            return null;
        }
        try {
            TrustManager[] trustManagers = null;
            if (truststore != null) {
                KeyStore store = load(settings, "tls.truststore");
                TrustManagerFactory factory = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                factory.init(store);
                trustManagers = factory.getTrustManagers();
            }
            KeyManager[] keyManagers = null;
            if (keystore != null) {
                KeyStore store = load(settings, "tls.keystore");
                KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(store, password(settings, "tls.keystore"));
                keyManagers = factory.getKeyManagers();
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw settings.invalid("cannot initialize TLS: " + e.getMessage());
        }
    }

    static KeyStore load(SinkSettings settings, String key) throws GeneralSecurityException {
        Path path = Path.of(settings.require(key + ".path"));
        try (InputStream in = Files.newInputStream(path)) {
            KeyStore store = KeyStore.getInstance(settings.get(key + ".type", "PKCS12"));
            store.load(in, password(settings, key));
            return store;
        } catch (IOException e) {
            throw settings.invalid("cannot read " + settings.key(key + ".path") + ": " + e.getMessage());
        }
    }

    static char[] password(SinkSettings settings, String key) {
        String password = settings.get(key + ".password");
        return password == null ? null : password.toCharArray();
    }
}
