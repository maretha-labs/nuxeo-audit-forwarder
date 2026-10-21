/*
 * Copyright 2026 Maretha Solutions
 * SPDX-License-Identifier: Apache-2.0
 */
package io.maretha.audit.forwarder.transport;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.net.URI;
import java.time.Duration;

import org.nuxeo.common.Environment;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.aws.AWSConfigurationService;
import org.nuxeo.runtime.aws.NuxeoAWSRegionProvider;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSSessionCredentials;

import io.maretha.audit.forwarder.config.SinkSettings;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

/**
 * Builds AWS SDK clients the way Nuxeo builds its own: credentials, region and TLS from {@code nuxeo-runtime-aws}
 * (falling back to the AWS default chains), optionally a role assumed through AWS STS. Static keys in the sink
 * settings take precedence when both are set.
 * <p>
 * On LTS 2023 {@code nuxeo-runtime-aws} is written for AWS SDK v1: its settings are carried over to these SDK v2
 * clients the way Nuxeo 2025 applies them.
 */
public class AwsClients implements AutoCloseable {

    protected static final AwsCredentialsProvider DEFAULT_CREDENTIALS = DefaultCredentialsProvider.builder().build();

    protected final SqsClient sqs;

    protected StsClient sts;

    protected StsAssumeRoleCredentialsProvider roleCredentials;

    public AwsClients(SinkSettings settings) {
        String configId = settings.get("aws.config");
        Region region = region(settings, configId);
        AwsCredentialsProvider credentials = baseCredentials(settings, configId);
        String roleArn = settings.get("role.arn");
        if (roleArn != null) {
            var stsBuilder = StsClient.builder()
                                      .httpClientBuilder(httpClient(settings, configId))
                                      .region(region)
                                      .credentialsProvider(credentials);
            String stsEndpoint = settings.get("sts.endpoint");
            if (stsEndpoint != null) {
                stsBuilder.endpointOverride(URI.create(stsEndpoint));
            }
            sts = stsBuilder.build();
            var request = AssumeRoleRequest.builder()
                                           .roleArn(roleArn)
                                           .externalId(settings.get("role.external.id"))
                                           .roleSessionName(settings.get("role.session.name", "nuxeo-audit-forwarder"))
                                           .build();
            roleCredentials = StsAssumeRoleCredentialsProvider.builder()
                                                              .stsClient(sts)
                                                              .refreshRequest(request)
                                                              .build();
            credentials = roleCredentials;
        }
        var builder = SqsClient.builder()
                               .httpClientBuilder(httpClient(settings, configId))
                               .region(region)
                               .credentialsProvider(credentials);
        String endpoint = settings.get("endpoint");
        if (endpoint != null) {
            builder.endpointOverride(URI.create(endpoint));
        }
        sqs = builder.build();
    }

    public SqsClient sqs() {
        return sqs;
    }

    protected static Region region(SinkSettings settings, String configId) {
        String region = settings.get("region");
        if (region != null) {
            return Region.of(region);
        }
        Region fromNuxeo = runtimeAvailable() ? Region.of(new NuxeoAWSRegionProvider(configId).getRegion()) : null;
        if (fromNuxeo == null) {
            throw settings.invalid("no AWS region: set " + settings.key("region") + " or nuxeo.aws.region");
        }
        return fromNuxeo;
    }

    protected static AwsCredentialsProvider baseCredentials(SinkSettings settings, String configId) {
        String keyId = settings.get("access.key.id");
        String secret = settings.get("secret.access.key");
        if (keyId != null || secret != null) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(settings.require("access.key.id"), settings.require("secret.access.key")));
        }
        return nuxeoCredentials(configId);
    }

    /** The nuxeo.conf AWS configuration, else the SDK v2 default chain: Nuxeo 2025's provider, over the v1 service. */
    protected static AwsCredentialsProvider nuxeoCredentials(String configId) {
        return () -> {
            AWSConfigurationService service = runtimeAvailable() ? Framework.getService(AWSConfigurationService.class)
                    : null;
            AWSCredentials credentials = service == null ? null : service.getAWSCredentials(configId);
            if (credentials == null) {
                return DEFAULT_CREDENTIALS.resolveCredentials();
            }
            if (credentials instanceof AWSSessionCredentials session) {
                return AwsSessionCredentials.create(session.getAWSAccessKeyId(), session.getAWSSecretKey(),
                        session.getSessionToken());
            }
            return AwsBasicCredentials.create(credentials.getAWSAccessKeyId(), credentials.getAWSSecretKey());
        };
    }

    protected static ApacheHttpClient.Builder httpClient(SinkSettings settings, String configId) {
        var builder = ApacheHttpClient.builder()
                                      .connectionTimeout(settings.getMillis("connect.timeout.ms", 10000))
                                      .socketTimeout(settings.getMillis("request.timeout.ms", 30000))
                                      .connectionMaxIdleTime(Duration.ofMinutes(1));
        AWSConfigurationService service = runtimeAvailable() ? Framework.getService(AWSConfigurationService.class)
                : null;
        if (service != null) {
            if (settings.getBoolean("proxy", false)) {
                configureProxy(builder);
            }
            // the v1 service builds the TLS socket factory on a v1 configuration; both SDKs use Apache HttpClient 4
            ClientConfiguration v1 = new ClientConfiguration();
            if (configId == null) {
                service.configureSSL(v1);
            } else {
                service.configureSSL(configId, v1);
            }
            var socketFactory = v1.getApacheHttpClientConfig().getSslSocketFactory();
            if (socketFactory != null) {
                builder.socketFactory(socketFactory);
            }
        }
        return builder;
    }

    /** The nuxeo.http.proxy.* settings, as Nuxeo 2025's {@code AWSConfigurationService.configureProxy} reads them. */
    protected static void configureProxy(ApacheHttpClient.Builder builder) {
        String proxyHost = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_HOST);
        String proxyPort = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_PORT);
        String proxyLogin = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_LOGIN);
        String proxyPassword = Framework.getProperty(Environment.NUXEO_HTTP_PROXY_PASSWORD);
        var config = ProxyConfiguration.builder();
        if (isNotBlank(proxyHost) && isNotBlank(proxyPort)) {
            config.endpoint(URI.create("http://" + proxyHost.toLowerCase() + ":" + proxyPort));
        }
        if (isNotBlank(proxyLogin)) {
            config.username(proxyLogin);
        }
        if (proxyPassword != null) { // could be blank
            config.password(proxyPassword);
        }
        builder.proxyConfiguration(config.build());
    }

    protected static boolean runtimeAvailable() {
        return Framework.getRuntime() != null;
    }

    @Override
    public void close() {
        sqs.close();
        if (roleCredentials != null) {
            roleCredentials.close();
        }
        if (sts != null) {
            sts.close();
        }
    }
}
