"""Benchmark queries on Dropwizard at 46bb4ed819, fixed before its first run (README.md, Queries).

The rules' picks (pick.py, main module dropwizard-core): the widely used method is
Environment.jersey() (78 call sites by text); the common name is `build` (225 files), at
DefaultServerFactory.build; the interface is ConfiguredBundle (8 implementing files), its method
`run`; the field read through a getter is Environment.name (getName, 25 other files); the base class
is Application (44 subclasses). dropwizard-core has no class of static members only and no enum, so
the static utility method and the enum constant queries are left out.
The other kinds sit in dropwizard-example's HelloWorldApplication, a jersey() call site, and in
DefaultServerFactory.build, for library and JDK calls.
Corrected after the priming run, where both servers answered alike: setHandler is Jetty's
Handler.Wrapper's (Server inherits it), and MigrationsBundle does not override run.
"""

CORE = "dropwizard-core/src/main/java/io/dropwizard/core/"
APP = "dropwizard-example/src/main/java/com/example/helloworld/HelloWorldApplication.java"
ENVIRONMENT = CORE + "setup/Environment.java"
SERVER_FACTORY = CORE + "server/DefaultServerFactory.java"

PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (APP, "environment.jersey().register(DateRequiredFeature.class);", "jersey"),
        "expect": {"locations": ["Environment.java:public JerseyEnvironment jersey()"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (APP, "new HelloWorldApplication().run(args);", "run"),
        "expect": {"locations": ["Application.java:public void run(String... arguments)"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (SERVER_FACTORY, "server.setHandler(addGracefulHandler(gzipHandler));", "setHandler"),
        "expect": {"library": "/Handler.java"},
    },
    {
        "name": "definition: JDK method",
        "method": "textDocument/definition",
        "at": (SERVER_FACTORY, "handlers.put(connector, applicationHandler);", "put"),
        "expect": {"library": "/Map.java"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (SERVER_FACTORY, "handlers.put(connector, applicationHandler);", "handlers"),
        "expect": {"library": "/Map.java"},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (SERVER_FACTORY, "server.addConnector(connector);", "addConnector"),
        "expect": {"hover_contains": ["addConnector", "Connector"]},
    },
    {
        "name": "references: widely used method",
        "method": "textDocument/references",
        "at": (ENVIRONMENT, "public JerseyEnvironment jersey()", "jersey"),
        "expect": {"min_count": 70},
    },
    {
        "name": "references: one overload",
        "method": "textDocument/references",
        "at": (CORE + "setup/Bootstrap.java", "public void addCommand(ConfiguredCommand<T> command)", "addCommand"),
        "expect": {"locations": ["HelloWorldApplication.java:bootstrap.addCommand(new RenderCommand());"]},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (APP, "final PersonDAO dao = new PersonDAO", "dao"),
        "expect": {"min_count": 3},
    },
    {
        "name": "implementation: subclasses of the base class",
        "method": "textDocument/implementation",
        "at": (CORE + "Application.java", "public abstract class Application<T extends Configuration>", "Application"),
        "expect": {"contains": ["HelloWorldApplication"]},
    },
    {
        "name": "typeHierarchy: supertypes",
        "method": "typeHierarchy/supertypes",
        "at": (APP, "public class HelloWorldApplication extends", "HelloWorldApplication"),
        "expect": {"contains": ["Application"]},
    },
    {
        "name": "typeHierarchy: subtypes of the base class",
        "method": "typeHierarchy/subtypes",
        "at": (CORE + "Application.java", "public abstract class Application<T extends Configuration>", "Application"),
        "expect": {"contains": ["HelloWorldApplication"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (ENVIRONMENT, "public JerseyEnvironment jersey()", "jersey"),
        "expect": {"contains": ["build", "run"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (SERVER_FACTORY, "public Server build(Environment environment)", "build"),
        "expect": {"contains": ["printBanner", "createThreadPool", "buildServer", "jersey"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (SERVER_FACTORY, "server.setHandler(addGracefulHandler(gzipHandler));", "addGracefulHandler"),
        "expect": {"signature_contains": "setHandler("},
    },
    {
        "name": "documentSymbol",
        "method": "textDocument/documentSymbol",
        "file": ENVIRONMENT,
        "expect": {"contains": ["Environment", "jersey", "lifecycle", "metrics"]},
    },
    {
        "name": "workspace/symbol: type-name prefix",
        "method": "workspace/symbol",
        "query": "ConfiguredBund",
        "expect": {"contains": ["ConfiguredBundle"]},
    },
    {
        "name": "references: common name",
        "method": "textDocument/references",
        "at": (SERVER_FACTORY, "public Server build(Environment environment)", "build"),
        "expect": {"locations": ["VirtualThreadsTest.java:defaultServerFactory.build(environment)"]},
    },
    {
        "name": "implementation: interface method",
        "method": "textDocument/implementation",
        "at": (CORE + "ConfiguredBundle.java", "default void run(T configuration, Environment environment)", "run"),
        "expect": {"contains": ["AssetsBundle", "HibernateBundle", "ViewBundle"]},
    },
    {
        "name": "references: field read through a getter",
        "method": "textDocument/references",
        "at": (ENVIRONMENT, "private final String name;", "name"),
        "expect": {"locations": ["Environment.java:return name;"]},
    },
]
