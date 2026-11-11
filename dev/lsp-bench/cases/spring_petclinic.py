# Copyright 2026 Gobinath Loganathan
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Copied unchanged from java-index's benchmark/cases.py at e59c8b3
# (https://github.com/slgobinath/java-index), so the results compare with its published ones.

"""Benchmark queries on spring-petclinic, fixed before any server was run.

A position is (file, a unique substring of the line, the token on that line, occurrence of the
token within the line). `expect` is a hand-written check of the answer; set-valued answers
(references, implementations, hierarchies) are also compared against jdtls in the report.
"""

PKG = "src/main/java/org/springframework/samples/petclinic/"
OWNER_CONTROLLER = PKG + "owner/OwnerController.java"

# The readiness probe: a definition into project code that every server must answer.
PROBE = "definition: project method"

CASES = [
    {
        "name": "definition: project method",
        "method": "textDocument/definition",
        "at": (OWNER_CONTROLLER, "return owners.findByLastNameStartingWith", "findByLastNameStartingWith"),
        "expect": {"locations": ["OwnerRepository.java:findByLastNameStartingWith"]},
    },
    {
        "name": "definition: inherited method",
        "method": "textDocument/definition",
        "at": (OWNER_CONTROLLER, 'return "redirect:/owners/" + owner.getId();', "getId"),
        "expect": {"locations": ["BaseEntity.java:getId"]},
    },
    {
        "name": "definition: library method",
        "method": "textDocument/definition",
        "at": (OWNER_CONTROLLER, "PageRequest.of(validatedPage - 1, pageSize)", "of"),
        "expect": {"library": "PageRequest"},
    },
    {
        "name": "definition: JDK method after lambda",
        "method": "textDocument/definition",
        "at": (OWNER_CONTROLLER, "Owner owner = optionalOwner.orElseThrow(", "orElseThrow"),
        "expect": {"library": "Optional"},
    },
    {
        "name": "typeDefinition: local variable",
        "method": "textDocument/typeDefinition",
        "at": (OWNER_CONTROLLER, "Owner owner = optionalOwner.orElseThrow(", "optionalOwner"),
        "expect": {"library": "Optional"},
    },
    {
        "name": "hover: library method",
        "method": "textDocument/hover",
        "at": (OWNER_CONTROLLER, "List<Owner> listOwners = paginated.getContent();", "getContent"),
        "expect": {"hover_contains": ["getContent", "List"]},
    },
    {
        "name": "references: getId (many callers)",
        "method": "textDocument/references",
        "at": (PKG + "model/BaseEntity.java", "public Integer getId()", "getId"),
        "expect": {"min_count": 3},
    },
    {
        "name": "references: one overload of getPet",
        "method": "textDocument/references",
        "at": (PKG + "owner/Owner.java", "public Pet getPet(String name) {", "getPet"),
        "expect": {"min_count": 1},
    },
    {
        "name": "documentHighlight: local variable",
        "method": "textDocument/documentHighlight",
        "at": (OWNER_CONTROLLER, "String lastName = owner.getLastName();", "lastName"),
        "expect": {"min_count": 5},
    },
    {
        "name": "implementation: subclasses of BaseEntity",
        "method": "textDocument/implementation",
        "at": (PKG + "model/BaseEntity.java", "public class BaseEntity", "BaseEntity"),
        "expect": {"contains": ["NamedEntity", "Person", "Owner", "Pet", "Visit"]},
    },
    {
        "name": "typeHierarchy: supertypes of Owner",
        "method": "typeHierarchy/supertypes",
        "at": (PKG + "owner/Owner.java", "public class Owner extends Person", "Owner"),
        "expect": {"contains": ["Person"]},
    },
    {
        "name": "typeHierarchy: subtypes of BaseEntity",
        "method": "typeHierarchy/subtypes",
        "at": (PKG + "model/BaseEntity.java", "public class BaseEntity", "BaseEntity"),
        "expect": {"contains": ["NamedEntity", "Person", "Visit"]},
    },
    {
        "name": "callHierarchy: incoming calls",
        "method": "callHierarchy/incomingCalls",
        "at": (OWNER_CONTROLLER, "private Page<Owner> findPaginatedForOwnersLastName", "findPaginatedForOwnersLastName"),
        "expect": {"contains": ["processFindForm"]},
    },
    {
        "name": "callHierarchy: outgoing calls",
        "method": "callHierarchy/outgoingCalls",
        "at": (OWNER_CONTROLLER, "private Page<Owner> findPaginatedForOwnersLastName", "findPaginatedForOwnersLastName"),
        "expect": {"contains": ["findByLastNameStartingWith", "of"]},
    },
    {
        "name": "signatureHelp: library call",
        "method": "textDocument/signatureHelp",
        "at": (OWNER_CONTROLLER, "PageRequest.of(validatedPage - 1, pageSize)", "validatedPage"),
        "expect": {"signature_contains": "of("},
    },
    {
        "name": "documentSymbol: Owner.java",
        "method": "textDocument/documentSymbol",
        "file": PKG + "owner/Owner.java",
        "expect": {"contains": ["Owner", "getPets", "addVisit"]},
    },
    {
        "name": "workspace/symbol: PetVal",
        "method": "workspace/symbol",
        "query": "PetVal",
        "expect": {"contains": ["PetValidator"]},
    },
]

# Completion, pre-registered in the 2026-11-11 amendment: a line typed into the definition query's file
# (after the anchor line, inside a method), completed at its end; the expected item must rank within
# the first 20, and the unimported type's must add its import. These are Lathe's own, not java-index's.
CREATION_FORM = "public String initCreationForm() {"
CASES += [
    {
        "name": "completion: member of a project type",
        "method": "textDocument/completion",
        "edit": (OWNER_CONTROLLER, CREATION_FORM, "\t\tOwner latheBench = new Owner(); latheBench."),
        "expect": {"completion": "getPets"},
    },
    {
        "name": "completion: an unimported project type",
        "method": "textDocument/completion",
        "edit": (OWNER_CONTROLLER, CREATION_FORM, "\t\tSpe"),
        "expect": {"completion": "Specialty", "import": "org.springframework.samples.petclinic.vet.Specialty"},
    },
    {
        "name": "completion: a JDK type's static member",
        "method": "textDocument/completion",
        "edit": (OWNER_CONTROLLER, CREATION_FORM, "\t\tList."),
        "expect": {"completion": "of"},
    },
    {
        "name": "completion: a lambda parameter",
        "method": "textDocument/completion",
        "edit": (OWNER_CONTROLLER, CREATION_FORM,
                 "\t\tjava.util.function.Function<Owner, String> latheBench = o -> o."),
        "expect": {"completion": "getLastName"},
    },
]
