"""Benchmark queries on Helidon at 2f6df16425, fixed before its first run (README.md, Queries).

The rules' picks (pick.py, main module webserver/webserver): the widely used method is
ProxyProtocolHandler.handleAnyProtocol (37 call sites by text, most in its test); the common name is
`create` (2505 files), at ConnectionProviders.create; the static utility method is
LifecycleFailures.add (47 calls); the interface is HttpService (30 implementing files), its method
`routing`; the field read through a getter is LoomServer.running (isRunning); the enum constant is
ProxyProtocolData.Family.UNKNOWN; the base class is HttpRouteBase (6 subclasses).
The other kinds sit in LoomServer, the server itself. The module calls no third-party library, so
the library queries ask about the JDK, as the JDK method query does.
"""
import queries as Q

WS = "webserver/webserver/src/main/java/io/helidon/webserver/"
LOOM = WS + "LoomServer.java"
PROXY = WS + "ProxyProtocolHandler.java"
ROUTE_BASE = WS + "http/HttpRouteBase.java"

PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (LOOM, "featureContext.setUpFeature(feature);", "setUpFeature"),
        "expect": {"locations": ["ServerFeatureContextImpl.java:void setUpFeature(ServerFeature feature)"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (WS + "KeyPerformanceIndicatorContextFactory.java", "recordStartTime(); // Overwrite", "recordStartTime"),
        "expect": {"locations": ["KeyPerformanceIndicatorContextFactory.java:protected void recordStartTime()"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (LOOM, "if (running.compareAndSet(false, true)) {", "compareAndSet"),
        "expect": {"library": "/AtomicBoolean.java"},
    },
    {
        "name": "definition: JDK method",
        "method": "textDocument/definition",
        "at": (LOOM, 'Objects.requireNonNull(material, "material");', "requireNonNull"),
        "expect": {"library": "/Objects.java"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (LOOM, "return listener == null ? -1 : listener.port();", "listener"),
        "expect": {"locations": ["ServerListener.java:class ServerListener"]},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (LOOM, "if (running.compareAndSet(false, true)) {", "compareAndSet"),
        "expect": {"hover_contains": ["compareAndSet", "boolean"]},
    },
    {
        "name": "references: widely used method",
        "method": "textDocument/references",
        "at": (PROXY, "static ProxyProtocolData handleAnyProtocol(InputStream socketInputStream)", "handleAnyProtocol"),
        "expect": {"min_count": 30},
    },
    {
        "name": "references: one overload",
        "method": "textDocument/references",
        "at": (ROUTE_BASE, "List<HttpRouteBase> routes(ConnectionContext ctx,", "routes"),
        "expect": {"locations": ["RouteCrawler.java:routes = nextRoute.routes(ctx, request, fullMatchedPath, fullMatchedPattern);"]},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (LOOM, "ServerFeatureContextImpl featureContext = ", "featureContext"),
        "expect": {"min_count": 3},
    },
    {
        "name": "implementation: subclasses of the base class",
        "method": "textDocument/implementation",
        "at": (ROUTE_BASE, "abstract class HttpRouteBase implements HttpRoute", "HttpRouteBase"),
        "expect": {"contains": ["ServiceRoute", "HttpRouteWrap", "HttpRouteImpl"]},
    },
    {
        "name": "typeHierarchy: supertypes",
        "method": "typeHierarchy/supertypes",
        "at": (WS + "http/ServiceRoute.java", "class ServiceRoute extends HttpRouteBase", "ServiceRoute"),
        "expect": {"contains": ["HttpRouteBase"]},
    },
    {
        "name": "typeHierarchy: subtypes of the base class",
        "method": "typeHierarchy/subtypes",
        "at": (ROUTE_BASE, "abstract class HttpRouteBase implements HttpRoute", "HttpRouteBase"),
        "expect": {"contains": ["ServiceRoute", "HttpRouteImpl"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (PROXY, "static ProxyProtocolData handleAnyProtocol(InputStream socketInputStream)", "handleAnyProtocol"),
        "expect": {"contains": ["get", "basicV1Test"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (LOOM, "public WebServer start() {", "start"),
        "expect": {"contains": ["flavor", "print", "lockInterruptibly", "startIt"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (LOOM, "listenerMap.put(name,", "name"),
        "expect": {"signature_contains": "put("},
    },
    {
        "name": "documentSymbol",
        "method": "textDocument/documentSymbol",
        "file": LOOM,
        "expect": {"contains": ["LoomServer", "start", "stop", "isRunning"]},
    },
    {
        "name": "workspace/symbol: type-name prefix",
        "method": "workspace/symbol",
        "query": "HttpServ",
        "expect": {"contains": ["HttpService"]},
    },
    {
        "name": "references: common name",
        "method": "textDocument/references",
        "at": (WS + "ConnectionProviders.java", "public static ConnectionProviders create(", "create"),
        "expect": {"min_count": 1},
    },
    {
        "name": "references: static utility method",
        "method": "textDocument/references",
        "at": (WS + "LifecycleFailures.java", "static Throwable add(Throwable failure, Throwable next)", "add"),
        "expect": {"locations": ["ServerListener.java:failure = LifecycleFailures.add(failure, e);"]},
    },
    {
        "name": "implementation: interface method",
        "method": "textDocument/implementation",
        "at": (WS + "http/HttpService.java", "void routing(HttpRules rules);", "routing"),
        "expect": {"contains": ["HealthService", "InfoService"]},
    },
    {
        "name": "references: field read through a getter",
        "method": "textDocument/references",
        "at": (LOOM, "private final AtomicBoolean running = new AtomicBoolean();", "running"),
        "expect": {"locations": ["LoomServer.java:return running.get();"]},
    },
    {
        "name": "references: enum constant as a switch label",
        "method": "textDocument/references",
        "at": (WS + "ProxyProtocolData.java", "UNKNOWN,", "UNKNOWN"),
        "expect": {"locations": ["ProxyProtocolHandler.java:case UNKNOWN, UNIX -> throw BAD_PROTOCOL_EXCEPTION;"]},
    },
]

# The editing loop (2026-11-11 amendment), in the definition query's file. WebServerConfig is generated
# from its blueprint by Helidon's processors.
_PROBE_FILE = next(c for c in CASES if c["name"] == PROBE)["at"][0]
CASES += Q.editing_cases(
    _PROBE_FILE, "public boolean isRunning() {",
    member=("        WebServerConfig latheBench = null; latheBench.", "port"),
    unimported=("Htt", "HttpRouting", "io.helidon.webserver.http.HttpRouting"),
    static=("        Math.", "max"),
    lambda_line=("        java.util.function.Function<WebServerConfig, Object> latheBench = c -> c.", "port"),
    removed_import="import io.helidon.common.context.Context;",
    rename_at=next(c for c in CASES if c["name"] == "references: widely used method")["at"])
