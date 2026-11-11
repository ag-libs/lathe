"""Benchmark queries on Hibernate ORM at 37616099b7, fixed before its first run (README.md, Queries).

The rules' picks (pick.py, main module hibernate-core): the widely used method is
KeywordRegistration.registerKeyword (1272 call sites by text); the common name is `getId` (4239
files), at EntityAction.getId; the static utility method is StringHelper.isNotEmpty (166 calls);
the interface is RepeatableContainer (54 implementing files), its method `value`; the field read
through a getter is Table.name (getName, 850 other files); the enum constant is CastType.DATE (31
other files); the base class is HibernateException (106 subclasses).
The other kinds sit in SessionImpl, the session itself.
Changed after the priming run: the probe was the constructor call `new PersistEvent(...)`, where
Lathe answers the class and jdtls the constructor; a probe must be a query whose answer no server
disputes, so the probe is a plain method call and the constructor call is a query of its own.
"""

CORE = "hibernate-core/src/main/java/org/hibernate/"
SESSION = CORE + "internal/SessionImpl.java"
STRINGS = CORE + "internal/util/StringHelper.java"

PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (SESSION, "return persistenceContext.getEntityHoldersByKey().entrySet().stream()", "getEntityHoldersByKey"),
        "expect": {"locations": ["PersistenceContext.java:Map<EntityKey,EntityHolder> getEntityHoldersByKey();"]},
    },
    {
        "name": "definition: constructor call",
        "method": "textDocument/definition",
        "at": (SESSION, "firePersist( new PersistEvent( entityName, object, this ) );", "PersistEvent"),
        "expect": {"locations": ["PersistEvent.java:public PersistEvent(@Nullable String entityName"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (SESSION, "checkOpen();", "checkOpen"),
        "expect": {"locations": ["SharedSessionContractImplementor.java:default void checkOpen()"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (SESSION, ".map( entry -> entityType.getJavaType().cast(", "getJavaType"),
        "expect": {"library": "metamodel/Type.java"},
    },
    {
        "name": "definition: JDK method",
        "method": "textDocument/definition",
        "at": (SESSION, "originalException.addSuppressed( suppressed );", "addSuppressed"),
        "expect": {"library": "/Throwable.java"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (SESSION, "map.put( HINT_FLUSH_MODE, getHibernateFlushMode() );", "map"),
        "expect": {"library": "/HashMap.java"},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (SESSION, ".map( entry -> entityType.getJavaType().cast(", "getJavaType"),
        "expect": {"hover_contains": ["getJavaType", "Class"]},
    },
    {
        "name": "references: widely used method",
        "method": "textDocument/references",
        "at": (CORE + "dialect/identifier/spi/KeywordRegistration.java", "void registerKeyword(String keyword);",
               "registerKeyword"),
        "expect": {"min_count": 1000},
    },
    {
        "name": "references: one overload",
        "method": "textDocument/references",
        "at": (STRINGS, "public static String qualify(String prefix, String name)", "qualify"),
        "expect": {"locations": ["PropertyValueException.java:qualify( entityName, propertyName )"]},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (SESSION, "final var map = new HashMap<>( getDefaultProperties() );", "map"),
        "expect": {"min_count": 3},
    },
    {
        "name": "implementation: subclasses of the base class",
        "method": "textDocument/implementation",
        "at": (CORE + "HibernateException.java", "public class HibernateException extends", "HibernateException"),
        "expect": {"contains": ["JDBCException", "MappingException", "QueryException"]},
    },
    {
        "name": "typeHierarchy: supertypes",
        "method": "typeHierarchy/supertypes",
        "at": (CORE + "MappingException.java", "public class MappingException extends", "MappingException"),
        "expect": {"contains": ["HibernateException"]},
    },
    {
        "name": "typeHierarchy: subtypes of the base class",
        "method": "typeHierarchy/subtypes",
        "at": (CORE + "HibernateException.java", "public class HibernateException extends", "HibernateException"),
        "expect": {"contains": ["JDBCException", "MappingException", "QueryException"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (CORE + "dialect/identifier/spi/KeywordRegistration.java", "void registerKeyword(String keyword);",
               "registerKeyword"),
        "expect": {"contains": ["registerKeywords"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (SESSION, "private void firePersist(final PersistEvent persistEvent)", "firePersist"),
        "expect": {"contains": ["checkNotReadOnly", "checkTransactionSyncStatus", "fireEventOnEachListener"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (SESSION, "map.put( HINT_FLUSH_MODE, getHibernateFlushMode() );", "HINT_FLUSH_MODE"),
        "expect": {"signature_contains": "put("},
    },
    {
        "name": "documentSymbol",
        "method": "textDocument/documentSymbol",
        "file": SESSION,
        "expect": {"contains": ["SessionImpl", "persist", "firePersist", "getManagedEntities"]},
    },
    {
        "name": "workspace/symbol: type-name prefix",
        "method": "workspace/symbol",
        "query": "RepeatableCont",
        "expect": {"contains": ["RepeatableContainer"]},
    },
    {
        "name": "references: common name",
        "method": "textDocument/references",
        "at": (CORE + "action/internal/EntityAction.java", "public Object getId() {", "getId"),
        "expect": {"min_count": 1},
    },
    {
        "name": "references: static utility method",
        "method": "textDocument/references",
        "at": (STRINGS, "public static boolean isNotEmpty(@Nullable String string)", "isNotEmpty"),
        "expect": {"min_count": 100},
    },
    {
        "name": "implementation: interface method",
        "method": "textDocument/implementation",
        "at": (CORE + "boot/models/annotations/spi/RepeatableContainer.java", "R[] value();", "value"),
        "expect": {"min_count": 40},
    },
    {
        "name": "references: field read through a getter",
        "method": "textDocument/references",
        "at": (CORE + "mapping/Table.java", "private Identifier name;", "name"),
        "expect": {"locations": ["Table.java:return name == null ? null : name.getText();"]},
    },
    {
        "name": "references: enum constant as a switch label",
        "method": "textDocument/references",
        "at": (CORE + "query/sqm/CastType.java", "DATE, TIME, TIMESTAMP,", "DATE"),
        "expect": {"locations": ["PostgresPlusDialect.java:case DATE:"]},
    },
]

# An error in another module (2026-11-11 amendment): the widely used method of the queries gains a
# parameter, saved; the open caller in another module must show the error at its call, then settle
# without it after the revert.
CASES += [
    {
        "name": "diagnostics: an error in another module",
        "method": "textDocument/publishDiagnostics",
        "upstream": (CORE + "dialect/identifier/spi/KeywordRegistration.java", "void registerKeyword(String keyword);", "void registerKeyword(int latheBench, String keyword);"),
        "downstream": ("hibernate-community-dialects/src/main/java/org/hibernate/community/dialect/CUBRIDDialect.java", "registerKeyword("),
    },
]
