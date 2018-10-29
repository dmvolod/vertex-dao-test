package com.redhat.dsevosty;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.hotrod.HotRodServer;
import org.infinispan.server.hotrod.configuration.HotRodServerConfigurationBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
public class DataGridVerticleTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataGridVerticle.class);

    private static final String HOTROD_SERVER_HOST = "127.0.0.1";
    private static final int HOTROD_SERVER_PORT = 11222;
    private static final String CACHE_NAME = "sdo";

    private static final String HTTP_HOST = "127.0.0.1";
    private static final int HTTP_PORT = 8080;

    private static EmbeddedCacheManager cacheManager = null;

    private static final UUID SDO_ID = SimpleDataObject.defaultId();

    private static final SimpleDataObject SDO = new SimpleDataObject(null, "name 1", null);

    private static int httpPort = 0;

    @BeforeAll
    public static void setUp(Vertx vertx, VertxTestContext context) throws InterruptedException {
        EmbeddedCacheManager cm = new DefaultCacheManager();
        LOGGER.debug("CacheManager: " + cm);
        cm.defineConfiguration(CACHE_NAME, new ConfigurationBuilder().build());
        Cache<UUID, SimpleDataObject> cache = cm.<UUID, SimpleDataObject>getCache(CACHE_NAME);
        LOGGER.debug("Cache: " + cache);
        cacheManager = cm;
        cache.put(SDO_ID, SDO);
        HotRodServer srv = new HotRodServer();
        HotRodServerConfigurationBuilder config = new HotRodServerConfigurationBuilder().host(HOTROD_SERVER_HOST)
                .defaultCacheName(CACHE_NAME).port(HOTROD_SERVER_PORT);
        srv.start(config.build(), cacheManager);
        LOGGER.info("HotRod Server " + srv + " started");
        DeploymentOptions options = new DeploymentOptions();
        JsonObject vertxConfig = new JsonObject();
        vertxConfig.put(DataGridVerticle.INFINISPAN_HOTROD_SERVER_HOST, HOTROD_SERVER_HOST);
        vertxConfig.put(DataGridVerticle.INFINISPAN_HOTROD_SERVER_PORT, HOTROD_SERVER_PORT);
        try {
            ServerSocket socket = new ServerSocket(httpPort);
            httpPort = socket.getLocalPort();
            socket.close();
        } catch (Exception e) {
            httpPort = HTTP_PORT;
        }
        vertxConfig.put(DataGridVerticle.INFINISPAN_HOTROD_SERVER_HOST, HOTROD_SERVER_HOST);
        vertxConfig.put(DataGridVerticle.VERTX_HTTP_SERVER_ENABLED, true);
        vertxConfig.put(DataGridVerticle.VERTX_HTTP_SERVER_PORT, httpPort);
        LOGGER.info("Configuring to run Vert.x HTTP Server on port: " + httpPort);
        options.setConfig(vertxConfig);
        vertx.deployVerticle(DataGridVerticle.class, options, context.succeeding(ar -> context.awaitCompletion(35, TimeUnit.SECONDS));
        // context.awaitCompletion();
    }

    @AfterAll
    public static void teardDown(Vertx vertx, VertxTestContext context) throws InterruptedException {
        vertx.close(context.succeeding(ar -> {
            context.completeNow();
        }));
        context.awaitCompletion(5, TimeUnit.SECONDS);
    }

    @Test
    public void directCacheTest(Vertx vertx, VertxTestContext context) throws InterruptedException {
        final SimpleDataObject sdo = new SimpleDataObject(null, "name 123", null);
        sdo.setOtherReference(sdo.getId());
        org.infinispan.client.hotrod.configuration.ConfigurationBuilder builder = new org.infinispan.client.hotrod.configuration.ConfigurationBuilder();
        builder.addServer().host(HOTROD_SERVER_HOST).port(HOTROD_SERVER_PORT);
        RemoteCache<UUID, SimpleDataObject> cache = new RemoteCacheManager(builder.build()).getCache(CACHE_NAME);
        LOGGER.info("GOT Remote Cache: " + cache);
        LOGGER.info("PUT OBJECT: " + cache.put(SDO_ID, sdo));
        SimpleDataObject sdo2 = cache.get(SDO_ID);
        LOGGER.info("GET OBJECT: " + sdo2.toJson().encodePrettily());
        assertThat(sdo2).isNotNull();
        assertThat(sdo2.getName()).isEqualTo(sdo.getName());
        context.completeNow();
    }

    // @Test
    public void testROOT(Vertx vertx, VertxTestContext context) {
        final HttpClient http = vertx.createHttpClient();
        http.getNow(HTTP_PORT, HTTP_HOST, "/", response -> response.handler(body -> {
            LOGGER.info(body.toString());
            context.completeNow();
        }));
    }

    // @Test
    public void createSDO(Vertx vertx, VertxTestContext context) {
        Checkpoint post = context.checkpoint();
        final HttpClient http = vertx.createHttpClient();
        final SimpleDataObject sdo = new SimpleDataObject(null, "name 2", null);
        http.post(HTTP_PORT, HTTP_HOST, "/sdo").handler(response -> {
            final int statusCode = response.statusCode();
            LOGGER.info("StatusCode on post request for sdo=" + sdo + " is " + statusCode);
            assertThat(statusCode).isEqualByComparingTo(HttpResponseStatus.CREATED.code());
            response.bodyHandler(body -> {
                assertThat(sdo).isEqualTo(new SimpleDataObject(new JsonObject(body)));
                post.flag();
            });
        }).end(sdo.toJson().toString());
        // post.awaitSuccess(1000);
        context.completeNow();
    }

    // @Test
    public void getSDO(Vertx vertx, VertxTestContext context) {
        Checkpoint get = context.checkpoint();
        final HttpClient http = vertx.createHttpClient();
        http.getNow(httpPort, HTTP_HOST, "/sdo/" + SDO_ID, response -> response.handler(body -> {
            LOGGER.info("StatusCode: " + response.statusCode() + "\nBody: " + body);
            assertThat(response.statusCode()).isEqualByComparingTo(HttpResponseStatus.OK.code());
            assertThat(SDO).isEqualTo(new SimpleDataObject(new JsonObject(body)));
            get.flag();
        }));
        // get.awaitSuccess(1000);
        context.completeNow();
    }

    public void updateSDO(Vertx vertx, VertxTestContext context) {
        Checkpoint update = context.checkpoint();
        SDO.setOtherReference(null);
        final HttpClient http = vertx.createHttpClient();
        http.put(httpPort, HTTP_HOST, "/sdo/" + SDO_ID).handler(response -> {
            final int statusCode = response.statusCode();
            LOGGER.info("StatusCode on update request for sdo=" + SDO + " is " + statusCode);
            assertThat(statusCode).isEqualTo(HttpResponseStatus.OK.code());
            response.bodyHandler(body -> {
                assertThat(SDO).isEqualTo(new SimpleDataObject(new JsonObject(body)));
                update.flag();
            });
        }).end(SDO.toJson().toString());
        // update.awaitSuccess(1000);

        Checkpoint delete = context.checkpoint();
        http.delete(httpPort, HTTP_HOST, "/sdo/" + SDO_ID).handler(response -> {
            final int statusCode = response.statusCode();
            LOGGER.info("StatusCode on delete request for sdo=" + SDO + " is " + statusCode);
            assertThat(statusCode).isEqualTo(HttpResponseStatus.NO_CONTENT.code());
            delete.flag();
        }).end();
        // delete.awaitSuccess(1000);

        Checkpoint get2 = context.checkpoint();
        http.getNow(httpPort, HTTP_HOST, "/sdo/" + SDO_ID, response -> {
            assertThat(response.statusCode()).isEqualTo(HttpResponseStatus.NOT_FOUND.code());
            get2.flag();
            context.completeNow();
        });
        // get2.awaitSuccess(1000);

        /*
         * if (post.isCompleted() && get.isCompleted() && update.isCompleted() &&
         * delete.isCompleted() && get2.isCompleted()) { async.complete(); }
         */
        // async.awaitSuccess(6000);
        // context.completeNow();
    }
}
