//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.osgi.test;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.toolchain.test.JDK;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * HTTP2 setup.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public class TestJettyOSGiBootHTTP2Conscrypt
{
    private static final String LOG_LEVEL = "WARN";


    @Inject
    private BundleContext bundleContext;

    @Configuration
    public Option[] config()
    {
        ArrayList<Option> options = new ArrayList<Option>();
        options.add(CoreOptions.junitBundles());
        options.addAll(TestJettyOSGiBootWithJsp.configureJettyHomeAndPort(true,"jetty-http2.xml"));
        options.add(CoreOptions.bootDelegationPackages("org.xml.sax", "org.xml.*", "org.w3c.*", "javax.xml.*", "javax.activation.*"));
        options.add(CoreOptions.systemPackages("com.sun.org.apache.xalan.internal.res","com.sun.org.apache.xml.internal.utils",
                                               "com.sun.org.apache.xml.internal.utils", "com.sun.org.apache.xpath.internal",
                                               "com.sun.org.apache.xpath.internal.jaxp", "com.sun.org.apache.xpath.internal.objects", 
                                               "sun.security", "sun.security.x509","sun.security.ssl"));
        options.addAll(http2JettyDependencies());

        options.addAll(TestOSGiUtil.coreJettyDependencies());
        options.addAll(TestOSGiUtil.jspDependencies());
        //deploy a test webapp
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("test-jetty-webapp").classifier("webbundle").versionAsInProject());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-conscrypt-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-http-client-transport").versionAsInProject().start());

        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(LOG_LEVEL));
        options.add(systemProperty("org.eclipse.jetty.LEVEL").value(LOG_LEVEL));
        options.add(CoreOptions.cleanCaches(true));
        return options.toArray(new Option[options.size()]);
    }

    public static List<Option> http2JettyDependencies()
    {
        List<Option> res = new ArrayList<Option>();
        res.add(CoreOptions.systemProperty("jetty.alpn.protocols").value("h2,http/1.1"));
        res.add(CoreOptions.systemProperty("jetty.http.port").value("0"));
        res.add(CoreOptions.systemProperty("jetty.ssl.port").value(String.valueOf(TestOSGiUtil.DEFAULT_SSL_PORT)));
        res.add(CoreOptions.systemProperty("jetty.sslContext.provider").value("Conscrypt"));
        
        res.add(wrappedBundle(mavenBundle().groupId("org.conscrypt").artifactId("conscrypt-openjdk-uber").version("1.0.0.RC11"))
                .imports("javax.net.ssl,*")
                .exports("org.conscrypt;version=1.0.0.RC11")
                .instructions("Bundle-NativeCode=META-INF/native/libconscrypt_openjdk_jni-linux-x86_64.so")
                .start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.osgi").artifactId("jetty-osgi-alpn").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-conscrypt-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-server").versionAsInProject().start());


        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-hpack").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.http2").artifactId("http2-server").versionAsInProject().start());
        return res;
    }
 
  
    @Ignore
    @Test
    public void assertAllBundlesActiveOrResolved() throws Exception
    {
        TestOSGiUtil.debugBundles(bundleContext);
        Bundle conscrypt = TestOSGiUtil.getBundle(bundleContext, "org.eclipse.jetty.alpn.conscrypt.server");
        TestOSGiUtil.diagnoseNonActiveOrNonResolvedBundle(conscrypt);
        assertNotNull(conscrypt);
        ServiceReference[] services = conscrypt.getRegisteredServices();
        assertNotNull(services);
        assertTrue(services.length > 0);
    }


    @Test
    public void testHTTP2() throws Exception
    {
        HTTP2Client client = new HTTP2Client();
        try 
        {
            String tmp = System.getProperty("boot.https.port");
            assertNotNull(tmp);
            int port = Integer.valueOf(tmp.trim()).intValue();
            
            Path path = Paths.get("src",  "test", "config");
            File base = path.toFile();
            File keys = path.resolve("etc").resolve("keystore").toFile();
            
            HTTP2Client http2Client = new HTTP2Client();
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyManagerPassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
            sslContextFactory.setTrustStorePath(keys.getAbsolutePath());
            sslContextFactory.setKeyStorePath(keys.getAbsolutePath());
            sslContextFactory.setTrustStorePassword("OBF:1vny1zlo1x8e1vnw1vn61x8g1zlu1vn4");
            sslContextFactory.setProvider("Conscrypt");
            HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client), sslContextFactory);
            Executor executor = new QueuedThreadPool();
            httpClient.setExecutor(executor);

            httpClient.start();

            ContentResponse response = httpClient.GET("https://localhost:"+port+"/jsp/jstl.jsp");
            assertEquals(200, response.getStatus());
            assertTrue(response.getContentAsString().contains("JSTL Example"));

        }
        finally
        {
            if (client != null) client.stop();
        }
    }

}
