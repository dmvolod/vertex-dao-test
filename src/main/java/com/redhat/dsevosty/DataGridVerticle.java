package com.redhat.dsevosty;

import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.Configuration;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;

import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;

public abstract class DataGridVerticle extends AbstractVerticle {

    private static final Logger LOGGER = LoggerFactory.getLogger(DataGridVerticle.class);
    private static final String HTTP_GET_PARAMETER_ID = "id";

    public static final String PUBLIC_CONTEXT_NAME = "vertx.http.api.root";

    public static String INFINISPAN_HOTROD_SERVER = "infinispan.hotrod.server";
    public static String INFINISPAN_HOTROD_SERVER_HOST = INFINISPAN_HOTROD_SERVER + ".host";
    public static String INFINISPAN_HOTROD_SERVER_PORT = INFINISPAN_HOTROD_SERVER + ".port";

    public static String VERTX_HTTP_SERVER = "vertx.http.server";
    public static String VERTX_HTTP_SERVER_HOST = VERTX_HTTP_SERVER + ".host";
    public static String VERTX_HTTP_SERVER_PORT = VERTX_HTTP_SERVER + ".port";
    public static String VERTX_HTTP_SERVER_ENABLED = VERTX_HTTP_SERVER + ".enabled";

    protected RemoteCacheManager manager;
    protected RemoteCache<UUID, AbstractDataObject> cache;
    protected String publicContextName = "dgv";

    protected boolean isRestInterfaceEnabled = false;
    protected boolean isGrpcInterfaceEnabled = false;
    protected boolean isEventBusInterfaceEnabled = false;

    protected String httpServerHost = "127.0.0.1";
    protected int httpServerPort = 8080;

    protected String hotrodServerHost = "127.0.0.1";
    protected int hotrodServerPort = 11222;

    @Override
    public void start(Future<Void> startFuture) throws Exception {
        LOGGER.info("Vertx uses LOGGER: {}, LoggerDelegate is {}", LOGGER, LOGGER.getDelegate());
        vertx.<RemoteCache<UUID, AbstractDataObject>>executeBlocking(future -> {
            Configuration managerConfig = getCacheManagerConfiguration();
            manager = new RemoteCacheManager(managerConfig);
            LOGGER.info("Created RemoteCacheManger={}", manager);
            final String cacheName = config().getString("cache-name");
            LOGGER.debug("Trying to get cache: {}", cacheName);
            RemoteCache<UUID, AbstractDataObject> newCache = manager.getCache(cacheName);
            LOGGER.debug("Got reference for RemoteCahe={}", newCache);
            future.complete(newCache);
        }, result -> {
            if (result.succeeded()) {
                cache = result.result();
                LOGGER.info("RemoteCacheManager={}, initialized, and RemoteCache={} connected", manager, cache);
                registerEndpointREST(startFuture);
            } else {
                LOGGER.error("Error Connecting cache", result.cause());
                startFuture.fail(result.cause());
            }
        });
    }

    @Override
    public void stop(Future<Void> stopFuture) throws Exception {
        super.stop(stopFuture);
        if (manager != null) {
            manager.stopAsync().whenCompleteAsync((e, ex) -> {
                stopFuture.complete();
            });
        } else {
            stopFuture.complete();
        }
    }

    protected void registerEndpointREST(Future<Void> startFuture) {
        // CORS support
        Set<String> allowHeaders = new HashSet<String>();
        allowHeaders.add("x-requested-with");
        allowHeaders.add("Access-Control-Allow-Origin");
        allowHeaders.add("origin");
        allowHeaders.add("Content-Type");
        allowHeaders.add("accept");

        Set<HttpMethod> allowMethods = new HashSet<>();
        allowMethods.add(HttpMethod.GET);
        allowMethods.add(HttpMethod.POST);
        allowMethods.add(HttpMethod.DELETE);
        allowMethods.add(HttpMethod.PATCH);
        allowMethods.add(HttpMethod.PUT);

        Router rootRouter = Router.router(vertx);
        rootRouter.route().handler(CorsHandler.create("*").allowedHeaders(allowHeaders).allowedMethods(allowMethods));
        rootRouter.route().handler(BodyHandler.create());

        if (isRestInterfaceEnabled == false && isGrpcInterfaceEnabled == false) {
            rootRouter.route("/").handler(this::getEmptyRoot);
        } else {
            rootRouter.route("/").handler(this::getInfoRoot);
            Router router = Router.router(vertx);
            rootRouter.mountSubRouter("/" + publicContextName, router);

            router.get("/:id").handler(this::getDataObject);
            router.post("/").handler(this::addDataObject);
            router.put("/:id").handler(this::updateDataObject);
            router.patch("/:id").handler(this::updateDataObject);
            router.delete("/:id").handler(this::removeDataObject);
        }

        LOGGER.debug("Creating HTTP server for host={}, port={}", httpServerHost, httpServerPort);
        vertx.createHttpServer().requestHandler(rootRouter::accept).listen(httpServerPort, httpServerHost, ar -> {
            if (ar.succeeded()) {
                LOGGER.info("Vert.x HTTP Server started: " + ar.result());
                startFuture.complete();
            } else {
                LOGGER.error("Error while starting Vert.x HTTP Server", ar.cause());
                startFuture.fail(ar.cause());
            }
        });
    }

    protected void getEmptyRoot(RoutingContext rc) {
        rc.response().putHeader("rc-type", "text/html").setStatusCode(HttpResponseStatus.OK.code())
                .end("<html><body>OK/</body></html>\n");
    }

    protected void getInfoRoot(RoutingContext rc) {
        rc.response().putHeader("rc-type", "text/html").setStatusCode(HttpResponseStatus.OK.code())
                .end("<html><body>" + publicContextName + "/</body></html>\n");
    }

    private void sendError(RoutingContext rc, UUID id, Throwable th) {
        LOGGER.error("Error occured while looking key {} in cache {}", th, id, cache.getName());
        rc.fail(HttpResponseStatus.INTERNAL_SERVER_ERROR.code());
        rc.fail(th);
    }

    protected abstract AbstractDataObject abstractObjectFromJson(JsonObject json);

    protected void getDataObject(RoutingContext rc) {
        final UUID id = UUID.fromString(rc.request().getParam(HTTP_GET_PARAMETER_ID));
        LOGGER.debug("Requesting (HTTP/GET) Cache for id={}...", id);
        cache.getAsync(id).whenComplete((result, th) -> {
            if (th != null) {
                sendError(rc, id, th);
                return;
            }
            final HttpServerResponse response = rc.response();
            if (result == null) {
                LOGGER.debug("Object Not found for id={}", id);
                response.setStatusCode(HttpResponseStatus.NOT_FOUND.code()).end();
            } else {
                JsonObject json = result.toJson();
                LOGGER.debug("GOT Object {} from Cache", json);
                response.setStatusCode(HttpResponseStatus.OK.code()).putHeader("context-type", "application/json");
                response.end(json.encode());
            }
            LOGGER.debug("Flushing response");
        });
    }

    protected void addDataObject(RoutingContext rc) {
        JsonObject o = rc.getBodyAsJson();
        LOGGER.debug("Requesting (HTTP/POST) for o={}...", o);
        AbstractDataObject ado = abstractObjectFromJson(o);
        final UUID id = ado.getId();
        cache.putAsync(id, ado).whenComplete((result, th) -> {
            LOGGER.debug("Cache PUT for id={}  completed with result: {}", id, result);
            if (th != null) {
                sendError(rc, id, th);
                return;
            }
            rc.response().setStatusCode(HttpResponseStatus.CREATED.code()).putHeader("content-type", "application/json")
                    .end(cache.get(id).toJson().toString());
        });
    }

    protected void updateDataObject(RoutingContext rc) {
        final UUID id = UUID.fromString(rc.request().getParam(HTTP_GET_PARAMETER_ID));
        JsonObject o = rc.getBodyAsJson();
        LOGGER.debug("Requesting (HTTP/PUT) Cache for id={} for object {}...", id, o);
        SimpleDataObject sdo = new SimpleDataObject(o);
        cache.replaceAsync(id, sdo).whenComplete((result, th) -> {
            LOGGER.info("Cache REPLACE for id={} completed with result: {}", id, result);
            if (th != null) {
                sendError(rc, id, th);
                return;
            }
            rc.response().setStatusCode(HttpResponseStatus.OK.code()).putHeader("content-type", "application/json")
                    .end(cache.get(id).toJson().toString());
        });
    }

    protected void removeDataObject(RoutingContext rc) {
        final UUID id = UUID.fromString(rc.request().getParam(HTTP_GET_PARAMETER_ID));
        LOGGER.debug("Requesting (HTTP/DELEETE) Cache for id={} for object {}...", id);
        cache.removeAsync(id).whenCompleteAsync((result, th) -> {
            LOGGER.info("Cache DELETE for id={} completed with result: {}", id, result);
            if (th != null) {
                sendError(rc, id, th);
                return;
            }
            rc.response().setStatusCode(HttpResponseStatus.NO_CONTENT.code()).end();
        });
    }

    protected void initConfiguration() {
        JsonObject vertxConfig = config();
        LOGGER.info("Vert.x config: " + vertxConfig);
        hotrodServerHost = vertxConfig.getString(INFINISPAN_HOTROD_SERVER_HOST, "127.0.0.1");
        hotrodServerPort = vertxConfig.getInteger(INFINISPAN_HOTROD_SERVER_PORT, 11222);
        httpServerHost = vertxConfig.getString(VERTX_HTTP_SERVER_HOST, "127.0.0.1");
        httpServerPort = vertxConfig.getInteger(VERTX_HTTP_SERVER_PORT, 8080);
        publicContextName = vertxConfig.getString(PUBLIC_CONTEXT_NAME, "dgv");
    }

    protected Configuration getCacheManagerConfiguration() {
        LOGGER.debug("Creating remote cache configuration for host={}, port={}", hotrodServerHost, hotrodServerPort);
        Configuration config = new ConfigurationBuilder().addServer().host(hotrodServerHost).port(hotrodServerPort)
                .build();
        return config;
    }
}
