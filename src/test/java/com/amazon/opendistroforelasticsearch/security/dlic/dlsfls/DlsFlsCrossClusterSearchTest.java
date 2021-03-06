/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License").
 *  You may not use this file except in compliance with the License.
 *  A copy of the License is located at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.security.dlic.dlsfls;

import org.apache.http.HttpStatus;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import com.amazon.opendistroforelasticsearch.security.test.AbstractSecurityUnitTest;
import com.amazon.opendistroforelasticsearch.security.test.DynamicSecurityConfig;
import com.amazon.opendistroforelasticsearch.security.test.helper.cluster.ClusterConfiguration;
import com.amazon.opendistroforelasticsearch.security.test.helper.cluster.ClusterHelper;
import com.amazon.opendistroforelasticsearch.security.test.helper.cluster.ClusterInfo;
import com.amazon.opendistroforelasticsearch.security.test.helper.rest.RestHelper;
import com.amazon.opendistroforelasticsearch.security.test.helper.rest.RestHelper.HttpResponse;

public class DlsFlsCrossClusterSearchTest extends AbstractSecurityUnitTest {

    private final ClusterHelper cl1 = new ClusterHelper("crl1_n"+num.incrementAndGet()+"_f"+System.getProperty("forkno")+"_t"+System.nanoTime());
    private final ClusterHelper cl2 = new ClusterHelper("crl2_n"+num.incrementAndGet()+"_f"+System.getProperty("forkno")+"_t"+System.nanoTime());
    private ClusterInfo cl1Info;
    private ClusterInfo cl2Info;

    @Override
    protected String getResourceFolder() {
        return "dlsfls";
    }

    private void setupCcs() throws Exception {

        System.setProperty("security.display_lic_none","true");

        cl2Info = cl2.startCluster(minimumSecuritySettings(Settings.EMPTY), ClusterConfiguration.DEFAULT);
        initialize(cl2Info, Settings.EMPTY, new DynamicSecurityConfig().setSecurityRoles("roles_983.yml"));
        System.out.println("### cl2 complete ###");

        //cl1 is coordinating
        cl1Info = cl1.startCluster(minimumSecuritySettings(crossClusterNodeSettings(cl2Info)), ClusterConfiguration.DEFAULT);
        System.out.println("### cl1 start ###");
        initialize(cl1Info, Settings.EMPTY, new DynamicSecurityConfig().setSecurityRoles("roles_983.yml"));
        System.out.println("### cl1 initialized ###");
    }

    @After
    public void tearDown() throws Exception {
        cl1.stopCluster();
        cl2.stopCluster();
    }

    private Settings crossClusterNodeSettings(ClusterInfo remote) {
        Settings.Builder builder = Settings.builder()
                .putList("search.remote.cross_cluster_two.seeds", remote.nodeHost+":"+remote.nodePort);
        return builder.build();
    }

    @Test
    public void testCcs() throws Exception {
        setupCcs();

        try (TransportClient tc = getInternalTransportClient(cl1Info, Settings.EMPTY)) {
            tc.index(new IndexRequest("twitter").type("tweet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0")
                    .source("{\"cluster\": \""+cl1Info.clustername+"\"}", XContentType.JSON)).actionGet();
        }

        try (TransportClient tc = getInternalTransportClient(cl2Info, Settings.EMPTY)) {
            tc.index(new IndexRequest("twutter").type("tweet").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0")
                    .source("{\"cluster\": \""+cl2Info.clustername+"\"}", XContentType.JSON)).actionGet();
            tc.index(new IndexRequest("humanresources").type("hr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("0")
                    .source("{\"cluster\": \""+cl2Info.clustername+"\","+
                              "\"Designation\": \"CEO\","+
                              "\"FirstName\": \"__fn__"+cl2Info.clustername+"\","+
                              "\"LastName\": \"lastname0\","+
                              "\"Salary\": \"salary0\","+
                              "\"SecretFiled\": \"secret0\","+
                              "\"AnotherSecredField\": \"anothersecret0\","+
                              "\"XXX\": \"xxx0\""
                            + "}", XContentType.JSON)).actionGet();

            tc.index(new IndexRequest("humanresources").type("hr").setRefreshPolicy(RefreshPolicy.IMMEDIATE).id("1")
                    .source("{\"cluster\": \""+cl2Info.clustername+"\","+
                              "\"Designation\": \"someoneelse\","+
                              "\"FirstName\": \"__fn__"+cl2Info.clustername+"\","+
                              "\"LastName\": \"lastname1\","+
                              "\"Salary\": \"salary1\","+
                              "\"SecretFiled\": \"secret1\","+
                              "\"AnotherSecredField\": \"anothersecret1\","+
                              "\"XXX\": \"xxx1\""
                            + "}", XContentType.JSON)).actionGet();

        }

        HttpResponse ccs = null;

        System.out.println("###################### query 1");
        //on coordinating cluster
        ccs = new RestHelper(cl1Info, false, false, getResourceFolder()).executeGetRequest("cross_cluster_two:humanresources/_search?pretty", encodeBasicHeader("human_resources_trainee", "password"));
        System.out.println(ccs.getBody());
        Assert.assertEquals(HttpStatus.SC_OK, ccs.getStatusCode());
        Assert.assertFalse(ccs.getBody().contains("crl1"));
        Assert.assertTrue(ccs.getBody().contains("crl2"));
        Assert.assertTrue(ccs.getBody().contains("\"total\" : 1"));
        Assert.assertFalse(ccs.getBody().contains("CEO"));
        Assert.assertFalse(ccs.getBody().contains("salary0"));
        Assert.assertFalse(ccs.getBody().contains("secret0"));
        Assert.assertTrue(ccs.getBody().contains("someoneelse"));
        Assert.assertTrue(ccs.getBody().contains("__fn__crl2"));
        Assert.assertTrue(ccs.getBody().contains("salary1"));
        Assert.assertFalse(ccs.getBody().contains("secret1"));
        Assert.assertFalse(ccs.getBody().contains("AnotherSecredField"));
        Assert.assertFalse(ccs.getBody().contains("xxx1"));
    }
}
