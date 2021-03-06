/*
 * Copyright 2014 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.waratek.cloudvm;

import static brooklyn.event.basic.DependentConfiguration.attributeWhenReady;
import static brooklyn.event.basic.DependentConfiguration.formatString;

import java.util.concurrent.TimeUnit;

import brooklyn.catalog.Catalog;
import brooklyn.catalog.CatalogConfig;
import brooklyn.config.ConfigKey;
import brooklyn.enricher.Enrichers;
import brooklyn.enricher.HttpLatencyDetector;
import brooklyn.entity.basic.AbstractApplication;
import brooklyn.entity.basic.ConfigKeys;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.StartableApplication;
import brooklyn.entity.database.mysql.MySqlNode;
import brooklyn.entity.group.DynamicCluster;
import brooklyn.entity.java.JavaEntityMethods;
import brooklyn.entity.java.UsesJmx;
import brooklyn.entity.java.UsesJmx.JmxAgentModes;
import brooklyn.entity.proxy.nginx.NginxController;
import brooklyn.entity.proxying.EntitySpec;
import brooklyn.entity.waratek.cloudvm.WaratekNodePlacementStrategy;
import brooklyn.entity.webapp.ControlledDynamicWebAppCluster;
import brooklyn.entity.webapp.DynamicWebAppCluster;
import brooklyn.entity.webapp.JavaWebAppService;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.entity.webapp.WebAppServiceConstants;
import brooklyn.entity.webapp.jetty.Jetty6Server;
import brooklyn.event.AttributeSensor;
import brooklyn.event.basic.Sensors;
import brooklyn.location.basic.PortRanges;
import brooklyn.policy.autoscaling.AutoScalerPolicy;

import com.google.common.collect.ImmutableMap;

/**
 * Launches a 3-tier app with {@link NginxController Nginx}, clustered
 * {@link Jetty6Server Jetty}, and {@link MySqlNode MySQL}.
 * <p>
 * Uses {@link JmxAgentModes#JMXMP JMXMP} for JMX access.
 *
 * @see TomcatClusterApplication
 */
@Catalog(name="Elastic Web Cluster",
        description="Load-balanced elastic Jetty cluster with a database.",
        iconUrl="classpath://glossy-3d-blue-web-icon.png")
public class JettyClusterApplication extends AbstractApplication implements StartableApplication {

    public static final String DEFAULT_WAR_PATH = "https://s3-eu-west-1.amazonaws.com/brooklyn-waratek/hello-world-sql.war";
    public static final String DEFAULT_DB_SETUP_SQL_URL = "https://s3-eu-west-1.amazonaws.com/brooklyn-waratek/visitors-creation-script.sql";

    @CatalogConfig(label="Jetty Cluster Size", priority=3)
    public static final ConfigKey<Integer> JETTY_CLUSTER_SIZE = ConfigKeys.newConfigKeyWithDefault(DynamicCluster.INITIAL_SIZE, 2);

    @CatalogConfig(label="War File (URL)", priority=2)
    public static final ConfigKey<String> WAR_PATH = ConfigKeys.newConfigKey(
            "app.war", "URL to the application archive which should be deployed", DEFAULT_WAR_PATH);

    @CatalogConfig(label="Database Setup SQL (URL)", priority=2)
    public static final ConfigKey<String> DB_SETUP_SQL_URL = ConfigKeys.newConfigKey(
            "app.db_sql", "URL to the SQL script to set up the database", DEFAULT_DB_SETUP_SQL_URL);

    public static final String DB_TABLE = "visitors";
    public static final String DB_USERNAME = "brooklyn";
    public static final String DB_PASSWORD = "br00k11n";

    public static final AttributeSensor<Integer> APPSERVERS_COUNT = Sensors.newIntegerSensor("appservers.count", "Number of app servers deployed");
    public static final AttributeSensor<Double> REQUESTS_PER_SECOND_IN_WINDOW = WebAppServiceConstants.REQUESTS_PER_SECOND_IN_WINDOW;
    public static final AttributeSensor<String> ROOT_URL = WebAppServiceConstants.ROOT_URL;

    @Override
    public void init() {
        MySqlNode mysql = addChild(EntitySpec.create(MySqlNode.class)
                .displayName("MySQL Database")
                .configure(MySqlNode.CREATION_SCRIPT_URL, Entities.getRequiredUrlConfig(this, DB_SETUP_SQL_URL)));

        ControlledDynamicWebAppCluster web = addChild(EntitySpec.create(ControlledDynamicWebAppCluster.class)
                .displayName("Jetty Cluster")
                .configure(WebAppService.HTTP_PORT, PortRanges.fromString("8080+"))
                .configure(ControlledDynamicWebAppCluster.MEMBER_SPEC, EntitySpec.create(Jetty6Server.class)
                        .configure(UsesJmx.USE_JMX, Boolean.TRUE)
                        .configure(UsesJmx.JMX_AGENT_MODE, JmxAgentModes.JMXMP)
                        .configure(UsesJmx.JMX_PORT, PortRanges.fromString("30000+")))
                .configure(ControlledDynamicWebAppCluster.WEB_CLUSTER_SPEC, EntitySpec.create(DynamicWebAppCluster.class)
                        .configure(DynamicCluster.ENABLE_AVAILABILITY_ZONES, true)
                        .configure(DynamicCluster.ZONE_PLACEMENT_STRATEGY, new WaratekNodePlacementStrategy()))
                .configure(JavaWebAppService.ROOT_WAR, Entities.getRequiredUrlConfig(this, WAR_PATH))
                .configure(JavaEntityMethods.javaSysProp("brooklyn.example.db.url"),
                        formatString("jdbc:%s%s?user=%s\\&password=%s", attributeWhenReady(mysql, MySqlNode.DATASTORE_URL), DB_TABLE, DB_USERNAME, DB_PASSWORD))
                .configure(DynamicCluster.INITIAL_SIZE, getConfig(JETTY_CLUSTER_SIZE)));

        web.addEnricher(HttpLatencyDetector.builder()
                .url(ROOT_URL)
                .rollup(10, TimeUnit.SECONDS)
                .build());
        web.getCluster().addPolicy(AutoScalerPolicy.builder()
                .metric(DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW_PER_NODE)
                .metricRange(10, 100)
                .sizeRange(2, 5)
                .build());

        addEnricher(Enrichers.builder()
                .propagating(WebAppServiceConstants.ROOT_URL, DynamicWebAppCluster.REQUESTS_PER_SECOND_IN_WINDOW, HttpLatencyDetector.REQUEST_LATENCY_IN_SECONDS_IN_WINDOW)
                .from(web)
                .build());
        addEnricher(Enrichers.builder()
                .propagating(ImmutableMap.of(DynamicWebAppCluster.GROUP_SIZE, APPSERVERS_COUNT))
                .from(web)
                .build());
    }

}
