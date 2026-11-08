"""Benchmark queries on Apache Fineract at d63beed7cc, fixed before its first run (README.md, Queries).

The rules' picks (pick.py, main module fineract-core): the widely used method is
DataValidatorBuilder.parameter (2609 call sites by text); the common name is `of` (1174 files), at
Money.of; the static utility method is DateUtils.getBusinessLocalDate (410 calls); the interface is
NewCommandSourceHandler (461 implementing files), its method `processCommand`; the field read
through a getter is Money.amount (getAmount, 125 other files); the enum constant is
PeriodFrequencyType.DAYS (19 other files); the base class is AbstractPlatformDomainRuleException
(197 subclasses).
The other kinds sit in GlobalConfigurationDataValidator, a parameter() call site that also calls
Gson, commons-lang3 and the JDK. The inherited method is BulkBusinessEvent's get(), not a
Lombok-generated one, so the query asks one thing.
"""

CORE = "fineract-core/src/main/java/org/apache/fineract/"
VALIDATOR = CORE + "infrastructure/configuration/data/GlobalConfigurationDataValidator.java"
BUILDER = CORE + "infrastructure/core/data/DataValidatorBuilder.java"
MONEY = CORE + "organisation/monetary/domain/Money.java"
RULE_EXCEPTION = CORE + "infrastructure/core/exception/AbstractPlatformDomainRuleException.java"

PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (VALIDATOR, "baseDataValidator.reset().parameter(GlobalConfigurationApiConstant.ENABLED)", "parameter"),
        "expect": {"locations": ["DataValidatorBuilder.java:public DataValidatorBuilder parameter(final String parameter)"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (CORE + "infrastructure/event/business/domain/BulkBusinessEvent.java",
               "return get().iterator().next().getAggregateRootId();", "get"),
        "expect": {"locations": ["AbstractBusinessEvent.java:public T get()"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (VALIDATOR, "if (StringUtils.isBlank(json)) {", "isBlank"),
        "expect": {"library": "/StringUtils.java"},
    },
    {
        "name": "definition: JDK method",
        "method": "textDocument/definition",
        "at": (VALIDATOR, "if (!dataValidationErrors.isEmpty()) {", "isEmpty"),
        "expect": {"library": "/List.java"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (VALIDATOR, "if (this.fromApiJsonHelper.parameterExists(GlobalConfigurationApiConstant.ENABLED, element)) {",
               "element"),
        "expect": {"library": "/JsonElement.java"},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (VALIDATOR, "final Type typeOfMap = new TypeToken<Map<String, Object>>() {}.getType();", "getType"),
        "expect": {"hover_contains": ["getType", "Type"]},
    },
    {
        "name": "references: widely used method",
        "method": "textDocument/references",
        "at": (BUILDER, "public DataValidatorBuilder parameter(final String parameter)", "parameter"),
        "expect": {"min_count": 2000},
    },
    {
        "name": "references: one overload",
        "method": "textDocument/references",
        "at": (MONEY, "public static Money of(final MonetaryCurrency currency, final BigDecimal newAmount) {", "of"),
        "expect": {"min_count": 1},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (VALIDATOR, "final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)",
               "baseDataValidator"),
        "expect": {"min_count": 5},
    },
    {
        "name": "implementation: subclasses of the base class",
        "method": "textDocument/implementation",
        "at": (RULE_EXCEPTION, "public abstract class AbstractPlatformDomainRuleException", "AbstractPlatformDomainRuleException"),
        "expect": {"min_count": 150},
    },
    {
        "name": "typeHierarchy: supertypes",
        "method": "typeHierarchy/supertypes",
        "at": (CORE + "organisation/monetary/exception/InvalidCurrencyException.java",
               "public class InvalidCurrencyException extends", "InvalidCurrencyException"),
        "expect": {"contains": ["AbstractPlatformDomainRuleException"]},
    },
    {
        "name": "typeHierarchy: subtypes of the base class",
        "method": "typeHierarchy/subtypes",
        "at": (RULE_EXCEPTION, "public abstract class AbstractPlatformDomainRuleException", "AbstractPlatformDomainRuleException"),
        "expect": {"contains": ["InvalidCurrencyException", "ProvisioningCriteriaCannotBeCreatedException"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (BUILDER, "public DataValidatorBuilder parameter(final String parameter)", "parameter"),
        "expect": {"contains": ["validateForUpdate", "validateAllowedValues"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (VALIDATOR, "public void validateForUpdate(final JsonCommand command)", "validateForUpdate"),
        "expect": {"contains": ["json", "isBlank", "checkForUnsupportedParameters", "parameter"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (VALIDATOR, "if (StringUtils.isBlank(json)) {", "json"),
        "expect": {"signature_contains": "isBlank("},
    },
    {
        "name": "documentSymbol",
        "method": "textDocument/documentSymbol",
        "file": BUILDER,
        "expect": {"contains": ["DataValidatorBuilder", "parameter", "notNull", "reset"]},
    },
    {
        "name": "workspace/symbol: type-name prefix",
        "method": "workspace/symbol",
        "query": "NewCommandSou",
        "expect": {"contains": ["NewCommandSourceHandler"]},
    },
    {
        "name": "references: common name",
        "method": "textDocument/references",
        "at": (MONEY, "public static Money of(final CurrencyData currency, final BigDecimal newAmount) {", "of"),
        "expect": {"min_count": 1},
    },
    {
        "name": "references: static utility method",
        "method": "textDocument/references",
        "at": (CORE + "infrastructure/core/service/DateUtils.java", "public static LocalDate getBusinessLocalDate()",
               "getBusinessLocalDate"),
        "expect": {"min_count": 300},
    },
    {
        "name": "implementation: interface method",
        "method": "textDocument/implementation",
        "at": (CORE + "commands/handler/NewCommandSourceHandler.java", "CommandProcessingResult processCommand(JsonCommand command);",
               "processCommand"),
        "expect": {"min_count": 400},
    },
    {
        "name": "references: field read through a getter",
        "method": "textDocument/references",
        "at": (MONEY, "private final BigDecimal amount;", "amount"),
        "expect": {"locations": ["Money.java:return amount;"]},
    },
    {
        "name": "references: enum constant as a switch label",
        "method": "textDocument/references",
        "at": (CORE + "portfolio/common/domain/PeriodFrequencyType.java", 'DAYS(0, "periodFrequencyType.days")', "DAYS"),
        "expect": {"locations": ["LoanEnumerations.java:case DAYS ->"]},
    },
]
