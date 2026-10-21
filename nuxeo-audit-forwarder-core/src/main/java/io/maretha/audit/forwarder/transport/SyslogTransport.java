/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.transport;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import io.maretha.audit.forwarder.config.SinkSettings;
import io.maretha.audit.forwarder.sink.SinkException;

/**
 * Sends syslog messages over UDP (RFC 5426), TCP (RFC 6587) or TLS (RFC 5425). Stream transports connect lazily,
 * reconnect before a batch when the receiver has closed the connection, and on the next send after an error. TCP and
 * TLS have no application-level acknowledgement: messages written while a connection breaks can still be lost.
 */
public abstract class SyslogTransport implements AutoCloseable {

    public enum Protocol {
        UDP(514), TCP(514), TLS(6514);

        final int defaultPort;

        Protocol(int defaultPort) {
            this.defaultPort = defaultPort;
        }
    }

    public enum Framing {
        /** {@code <length> <message>}, RFC 6587 section 3.4.1 */
        OCTET_COUNTING,
        /** message followed by LF, RFC 6587 section 3.4.2 */
        NEWLINE
    }

    protected final String host;

    protected final int port;

    protected SyslogTransport(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static SyslogTransport create(SinkSettings settings, String defaultProtocol, String defaultFraming) {
        Protocol protocol = Protocol.valueOf(
                settings.choice("protocol", defaultProtocol, "udp", "tcp", "tls").toUpperCase());
        String host = settings.require("host");
        int port = settings.getInt("port", protocol.defaultPort);
        if (port < 1 || port > 65535) {
            throw settings.invalid(settings.key("port") + " must be between 1 and 65535");
        }
        if (protocol == Protocol.UDP) {
            return new Udp(host, port, settings.getInt("udp.maxBytes", 8192));
        }
        Framing framing = Framing.valueOf(settings.choice("framing", defaultFraming, "octet_counting", "newline")
                                                  .toUpperCase());
        SSLContext sslContext = null;
        if (protocol == Protocol.TLS) {
            sslContext = TlsSupport.sslContext(settings);
            if (sslContext == null) {
                try {
                    sslContext = SSLContext.getDefault();
                } catch (java.security.NoSuchAlgorithmException e) {
                    throw settings.invalid("no default TLS context: " + e.getMessage());
                }
            }
        }
        return new Stream(host, port, framing, sslContext, settings.getMillis("connect.timeout.ms", 10000));
    }

    public abstract void send(List<String> messages) throws SinkException;

    public abstract String describe();

    @Override
    public abstract void close();

    static class Udp extends SyslogTransport {

        protected final int maxBytes;

        protected DatagramSocket socket;

        Udp(String host, int port, int maxBytes) {
            super(host, port);
            this.maxBytes = maxBytes;
        }

        @Override
        public void send(List<String> messages) throws SinkException {
            try {
                if (socket == null) {
                    socket = new DatagramSocket();
                }
                InetSocketAddress address = new InetSocketAddress(host, port);
                if (address.isUnresolved()) {
                    throw new SinkException("cannot resolve " + host);
                }
                for (String message : messages) {
                    byte[] data = message.getBytes(UTF_8);
                    if (data.length > maxBytes) {
                        data = Arrays.copyOf(data, maxBytes);
                    }
                    socket.send(new DatagramPacket(data, data.length, address));
                }
            } catch (IOException e) {
                close();
                throw new SinkException("cannot send to " + describe() + ": " + e, e);
            }
        }

        @Override
        public String describe() {
            return "udp://" + host + ":" + port;
        }

        @Override
        public void close() {
            if (socket != null) {
                socket.close();
                socket = null;
            }
        }
    }

    static class Stream extends SyslogTransport {

        protected final Framing framing;

        protected final SSLContext sslContext;

        protected final Duration connectTimeout;

        protected Socket socket;

        protected OutputStream out;

        Stream(String host, int port, Framing framing, SSLContext sslContext, Duration connectTimeout) {
            super(host, port);
            this.framing = framing;
            this.sslContext = sslContext;
            this.connectTimeout = connectTimeout;
        }

        @Override
        public void send(List<String> messages) throws SinkException {
            try {
                if (out != null && peerClosed()) {
                    close();
                }
                if (out == null) {
                    connect();
                }
                for (String message : messages) {
                    byte[] data = message.getBytes(UTF_8);
                    if (framing == Framing.OCTET_COUNTING) {
                        out.write((data.length + " ").getBytes(UTF_8));
                        out.write(data);
                    } else {
                        out.write(data);
                        out.write('\n');
                    }
                }
                out.flush();
            } catch (IOException e) {
                close();
                throw new SinkException("cannot send to " + describe() + ": " + e, e);
            }
        }

        /**
         * Syslog receivers never send data, so end-of-stream (or a reset) on the read side means the receiver closed
         * the connection, typically on restart. Writing to such a socket succeeds locally and the bytes are lost, so
         * the caller reconnects first.
         */
        protected boolean peerClosed() {
            try {
                socket.setSoTimeout(1);
                return socket.getInputStream().read() == -1;
            } catch (SocketTimeoutException e) {
                return false;
            } catch (IOException e) {
                return true;
            }
        }

        protected void connect() throws IOException {
            Socket plain = new Socket();
            plain.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            plain.setKeepAlive(true);
            if (sslContext != null) {
                SSLSocketFactory factory = sslContext.getSocketFactory();
                SSLSocket tls = (SSLSocket) factory.createSocket(plain, host, port, true);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                socket = tls;
            } else {
                socket = plain;
            }
            out = new BufferedOutputStream(socket.getOutputStream());
        }

        @Override
        public String describe() {
            return (sslContext != null ? "tls://" : "tcp://") + host + ":" + port;
        }

        @Override
        public void close() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // already broken
                }
            }
            socket = null;
            out = null;
        }
    }
}
