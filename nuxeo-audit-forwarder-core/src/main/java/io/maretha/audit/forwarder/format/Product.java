/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Identity of this add-on as it appears in CEF and LEEF headers. */
public final class Product {

    public static final String VENDOR = "Maretha";

    public static final String NAME = "Nuxeo Audit Forwarder";

    public static final String VERSION = loadVersion();

    private Product() {
    }

    private static String loadVersion() {
        try (InputStream in = Product.class.getResourceAsStream("product.properties")) {
            if (in != null) {
                Properties properties = new Properties();
                properties.load(in);
                String version = properties.getProperty("version");
                if (version != null && !version.startsWith("${")) {
                    return version;
                }
            }
        } catch (IOException e) {
            // fall through
        }
        return "dev";
    }
}
