/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport;

import javax.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.resolver.ClosableResolver;
import com.netflix.discovery.shared.resolver.ClusterResolver;
import com.netflix.discovery.shared.resolver.DefaultEndpoint;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.resolver.StaticClusterResolver;
import com.netflix.discovery.shared.resolver.aws.ApplicationsResolver;
import com.netflix.discovery.shared.resolver.aws.EurekaHttpResolver;
import com.netflix.discovery.shared.resolver.aws.TestEurekaHttpResolver;
import com.netflix.discovery.util.EurekaEntityComparators;
import com.netflix.discovery.util.InstanceInfoGenerator;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.netflix.discovery.shared.transport.EurekaHttpResponse.anEurekaHttpResponse;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Tomasz Bak
 */
public class EurekaHttpClientsTest {

    private static final InstanceInfo MY_INSTANCE = InstanceInfoGenerator.newBuilder(1, "myApp").build().first();
    private final EurekaClientConfig clientConfig = mock(EurekaClientConfig.class);
    private final EurekaTransportConfig transportConfig = mock(EurekaTransportConfig.class);
    private final EurekaInstanceConfig instanceConfig = mock(EurekaInstanceConfig.class);
    private final ApplicationInfoManager applicationInfoManager = new ApplicationInfoManager(instanceConfig, MY_INSTANCE);

    private final EurekaHttpClient writeRequestHandler = mock(EurekaHttpClient.class);
    private final EurekaHttpClient readRequestHandler = mock(EurekaHttpClient.class);

    private SimpleEurekaHttpServer writeServer;
    private SimpleEurekaHttpServer readServer;

    private ClusterResolver<EurekaEndpoint> clusterResolver;
    private EurekaHttpClientFactory clientFactory;

    private String readServerURI;

    private final InstanceInfoGenerator instanceGen = InstanceInfoGenerator.newBuilder(2, 1).build();

    @Before
    public void setUp() throws IOException {
        when(clientConfig.getEurekaServerTotalConnectionsPerHost()).thenReturn(10);
        when(clientConfig.getEurekaServerTotalConnections()).thenReturn(10);
        when(transportConfig.getSessionedClientReconnectIntervalSeconds()).thenReturn(10);

        writeServer = new SimpleEurekaHttpServer(writeRequestHandler);
        clusterResolver = new StaticClusterResolver<EurekaEndpoint>("regionA", new DefaultEndpoint("localhost", writeServer.getServerPort(), false, "/v2/"));

        readServer = new SimpleEurekaHttpServer(readRequestHandler);
        readServerURI = "http://localhost:" + readServer.getServerPort();

        clientFactory = EurekaHttpClients.canonicalClientFactory(
                "test",
                transportConfig,
                clusterResolver,
                EurekaHttpClients.newTransportClientFactory(
                        clientConfig,
                        applicationInfoManager.getInfo()
                ));
    }

    @After
    public void tearDown() throws Exception {
        if (writeServer != null) {
            writeServer.shutdown();
        }
        if (readServer != null) {
            readServer.shutdown();
        }
        if (clientFactory != null) {
            clientFactory.shutdown();
        }
    }

    @Test
    public void testCanonicalClient() throws Exception {
        Applications apps = instanceGen.toApplications();

        when(writeRequestHandler.getApplications()).thenReturn(
                anEurekaHttpResponse(302, Applications.class).headers("Location", readServerURI + "/v2/apps").build()
        );
        when(readRequestHandler.getApplications()).thenReturn(
                anEurekaHttpResponse(200, apps).headers(HttpHeaders.CONTENT_TYPE, "application/json").build()
        );

        EurekaHttpClient eurekaHttpClient = clientFactory.newClient();
        EurekaHttpResponse<Applications> result = eurekaHttpClient.getApplications();

        assertThat(result.getStatusCode(), is(equalTo(200)));
        assertThat(EurekaEntityComparators.equal(result.getEntity(), apps), is(true));
    }

    @Test
    public void testCanonicalResolver() throws Exception {
        when(clientConfig.getEurekaServerURLContext()).thenReturn("context");
        when(clientConfig.getRegion()).thenReturn("region");

        when(transportConfig.getAsyncExecutorThreadPoolSize()).thenReturn(3);
        when(transportConfig.getAsyncResolverRefreshIntervalMs()).thenReturn(300);
        when(transportConfig.getAsyncResolverWarmUpTimeoutMs()).thenReturn(200);

        Applications applications = InstanceInfoGenerator.newBuilder(5, "eurekaRead", "someOther").build().toApplications();
        String vipAddress = applications.getRegisteredApplications("eurekaRead").getInstances().get(0).getVIPAddress();

        ApplicationsResolver.ApplicationsSource applicationsSource = mock(ApplicationsResolver.ApplicationsSource.class);
        when(applicationsSource.getApplications(anyInt(), eq(TimeUnit.SECONDS)))
                .thenReturn(null)  // first time
                .thenReturn(applications);  // subsequent times

        EurekaHttpClientFactory remoteResolverClientFactory = mock(EurekaHttpClientFactory.class);
        EurekaHttpClient httpClient = mock(EurekaHttpClient.class);
        when(remoteResolverClientFactory.newClient()).thenReturn(httpClient);
        when(httpClient.getVip(vipAddress)).thenReturn(EurekaHttpResponse.anEurekaHttpResponse(200, applications).build());

        EurekaHttpResolver remoteResolver = spy(new TestEurekaHttpResolver(clientConfig, remoteResolverClientFactory, vipAddress));
        when(transportConfig.getReadClusterVip()).thenReturn(vipAddress);

        ApplicationsResolver localResolver = spy(new ApplicationsResolver(clientConfig, transportConfig, applicationsSource));

        ClosableResolver resolver = EurekaHttpClients.queryClientResolver(
                remoteResolver,
                localResolver,
                clientConfig,
                transportConfig,
                applicationInfoManager.getInfo()
        );

        List endpoints = resolver.getClusterEndpoints();
        assertThat(endpoints.size(), equalTo(applications.getInstancesByVirtualHostName(vipAddress).size()));
        verify(remoteResolver, times(1)).getClusterEndpoints();
        verify(localResolver, times(1)).getClusterEndpoints();

        // wait for the second cycle that hits the app source
        verify(applicationsSource, timeout(1000).times(2)).getApplications(anyInt(), eq(TimeUnit.SECONDS));
        endpoints = resolver.getClusterEndpoints();
        assertThat(endpoints.size(), equalTo(applications.getInstancesByVirtualHostName(vipAddress).size()));

        verify(remoteResolver, times(1)).getClusterEndpoints();
        verify(localResolver, times(2)).getClusterEndpoints();

    }
}