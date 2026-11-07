"""Benchmark queries on OpenMetadata at 9a2ee8b124, fixed before its first run (README.md, Queries).

The rules' picks (pick.py, main module openmetadata-service): the widely used method is
Entity.getEntityRepository (1086 call sites by text); the common name is `of` (2815 files), at
FollowerRecipientResolver.of; the static utility method is Entity.getEntityRepository again, so that
query is not repeated; the interface is EntityMapper (81 implementing files), its method
`createToEntity`; the field read through a getter is Fernet.instance (getInstance, 65 other files);
the enum constant is ConnectionType.MYSQL (10 other files); the base class is MigrationProcessImpl
(115 subclasses).
The other kinds sit in AutoPilotApp, a getEntityRepository() call site. The module's own code calls
little third-party API directly (its model classes are generated from JSON schemas, its logger and
getters by Lombok), so the library queries ask about the JDK, as in Helidon; the local variable's
type is such a generated class.
"""

SERVICE = "openmetadata-service/src/main/java/org/openmetadata/service/"
APP = SERVICE + "apps/bundles/autoPilot/AutoPilotApp.java"
ENTITY = SERVICE + "Entity.java"
MIGRATION = SERVICE + "migration/api/MigrationProcessImpl.java"

PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (APP, "BotRepository botRepository = (BotRepository) Entity.getEntityRepository(Entity.BOT);",
               "getEntityRepository"),
        "expect": {"locations": ["Entity.java:getEntityRepository("]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (APP, "super.uninstall();", "uninstall"),
        "expect": {"locations": ["AbstractNativeApplication.java:public void uninstall()"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (APP, "getTriggerWorkflowId(WORKFLOW_NAME), UUID.randomUUID().toString(), variables);", "randomUUID"),
        "expect": {"library": "/UUID.java"},
    },
    {
        "name": "definition: JDK method",
        "method": "textDocument/definition",
        "at": (APP, "appConfig.putAll(config);", "putAll"),
        "expect": {"library": "/Map.java"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (APP, "if (runtimeConfig.getActive()) {", "runtimeConfig"),
        "expect": {"locations": ["AutoPilotAppConfig.java:class AutoPilotAppConfig"]},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (APP, "return new String(in.readAllBytes());", "readAllBytes"),
        "expect": {"hover_contains": ["readAllBytes", "byte"]},
    },
    {
        "name": "references: widely used method",
        "method": "textDocument/references",
        "at": (ENTITY, "public static EntityRepository<? extends EntityInterface<?>> getEntityRepository(",
               "getEntityRepository"),
        "expect": {"min_count": 1000},
    },
    {
        "name": "references: one overload",
        "method": "textDocument/references",
        "at": (ENTITY, "public static <T> T getEntity(String entityType, UUID id, String fields, Include include)",
               "getEntity"),
        "expect": {"min_count": 10},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (APP, "BotRepository botRepository = (BotRepository)", "botRepository"),
        "expect": {"min_count": 3},
    },
    {
        "name": "implementation: subclasses of the base class",
        "method": "textDocument/implementation",
        "at": (MIGRATION, "public class MigrationProcessImpl implements MigrationProcess", "MigrationProcessImpl"),
        "expect": {"min_count": 100},
    },
    {
        "name": "typeHierarchy: supertypes",
        "method": "typeHierarchy/supertypes",
        "at": (SERVICE + "migration/mysql/v190/Migration.java", "public class Migration extends MigrationProcessImpl",
               "Migration"),
        "expect": {"contains": ["MigrationProcessImpl"]},
    },
    {
        "name": "typeHierarchy: subtypes of the base class",
        "method": "typeHierarchy/subtypes",
        "at": (MIGRATION, "public class MigrationProcessImpl implements MigrationProcess", "MigrationProcessImpl"),
        "expect": {"contains": ["Migration"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (ENTITY, "public static EntityRepository<? extends EntityInterface<?>> getEntityRepository(",
               "getEntityRepository"),
        "expect": {"contains": ["loadWorkflow", "createWorkflow", "deleteWorkflow"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (APP, "private WorkflowDefinition loadWorkflow() {", "loadWorkflow"),
        "expect": {"contains": ["getEntityRepository", "findByName", "getBotUser", "readResource"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (APP, ': resourceFile.replace("collate", "openmetadata");', "collate"),
        "expect": {"signature_contains": "replace("},
    },
    {
        "name": "documentSymbol",
        "method": "textDocument/documentSymbol",
        "file": APP,
        "expect": {"contains": ["AutoPilotApp", "install", "loadWorkflow", "createWorkflow"]},
    },
    {
        "name": "workspace/symbol: type-name prefix",
        "method": "workspace/symbol",
        "query": "MigrationProcessI",
        "expect": {"contains": ["MigrationProcessImpl"]},
    },
    {
        "name": "references: common name",
        "method": "textDocument/references",
        "at": (SERVICE + "alerting/audience/FollowerRecipientResolver.java",
               "private Recipients of(EntityInterface<?> entity, AddressDirectory directory)", "of"),
        "expect": {"locations": ["FollowerRecipientResolver.java:entity -> of(entity, directory)"]},
    },
    {
        "name": "implementation: interface method",
        "method": "textDocument/implementation",
        "at": (SERVICE + "mapper/EntityMapper.java", "T createToEntity(C create, String user);", "createToEntity"),
        "expect": {"min_count": 70},
    },
    {
        "name": "references: field read through a getter",
        "method": "textDocument/references",
        "at": (SERVICE + "fernet/Fernet.java", "private static final Fernet instance = new Fernet();", "instance"),
        "expect": {"locations": ["Fernet.java:return instance;"]},
    },
    {
        "name": "references: enum constant as a switch label",
        "method": "textDocument/references",
        "at": (SERVICE + "jdbi3/locator/ConnectionType.java", 'MYSQL("com.mysql.cj.jdbc.Driver"),', "MYSQL"),
        "expect": {"locations": ["OpenMetadataOperations.java:case MYSQL -> new MysqlAutoTuner();"]},
    },
]
