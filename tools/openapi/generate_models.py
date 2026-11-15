#!/usr/bin/env python3
"""Generate Kotlin API models and typed endpoints from an Immich OpenAPI spec.

Usage:
    python3 tools/openapi/generate_models.py \
        --spec tools/openapi/immich-v3.4.1.json \
        --out  app/src/main/kotlin/dev/immich/picker/api \
        --version 3.4.1

Outputs two files into --out:
  * Models.kt      - @Serializable models + enum value constants, for every schema
                     transitively referenced by the operations below.
  * Endpoints.kt   - one typed function per operation, delegating to the small
                     hand-written ApiClient runtime in dev.immich.picker.net.

The operation list is deliberately small: it is exactly what this app calls.
Everything else in the Immich API is irrelevant to a read-only DocumentsProvider.

Forward compatibility: unknown JSON keys are ignored at decode time, and every
enum is generated as a Kotlin `typealias ... = String` so that new members added
by a patch release can never throw a serialization error.
"""

from __future__ import annotations

import argparse
import json
import keyword
import os
import re
from typing import Any

# --------------------------------------------------------------------------------------
# Operations this app calls. Operation ids are taken verbatim from the Immich spec.
# --------------------------------------------------------------------------------------
OPERATIONS: list[str] = [
    "getServerVersion",
    "getVersionCheck",
    "pingServer",
    "login",
    "validateAccessToken",
    "getMyUser",
    "getAllAlbums",
    "getAlbumInfo",
    "getTimeBuckets",
    "getTimeBucket",
    "searchAssets",
    "getAssetInfo",
]

# Operations whose 200 response is raw bytes (handled by the binary runtime path).
BINARY_OPERATIONS: list[str] = [
    "downloadAsset",
    "viewAsset",
]

KOTLIN_SOFT_KEYWORDS = {
    "as", "fun", "in", "is", "object", "package", "typealias", "val", "var",
    "when", "while", "by", "catch", "constructor", "delegate", "dynamic",
    "field", "file", "finally", "get", "import", "init", "param", "property",
    "receiver", "set", "setparam", "where", "out", "companion", "internal",
}
KOTLIN_HARD_KEYWORDS = set(keyword.kwlist)
RESERVED = {k for k in (KOTLIN_HARD_KEYWORDS | KOTLIN_SOFT_KEYWORDS) if k}


def kotlin_ident(name: str) -> str:
    """Map a JSON property name to a Kotlin property identifier (camelCase)."""
    parts = [p for p in re.split(r"[^0-9a-zA-Z]+", name) if p]
    if not parts:
        return "value"
    # Preserve internal capitalisation differences: "assetId" and "assetID" must not
    # both collapse to "assetid" (Immich sends both spellings in different schemas).
    first = parts[0]
    rest = "".join(p[:1].upper() + p[1:] for p in parts[1:])
    ident = first[:1].lower() + first[1:] + rest
    if ident[0].isdigit():
        ident = "n" + ident
    return f"`{ident}`" if ident in RESERVED else ident


def param_ident(name: str) -> str:
    """Kotlin parameter name for an operation/query parameter.

    Lowercase-first but never merging words, so `assetId` -> `assetId`, not `assetid`.
    """
    ident = name[:1].lower() + name[1:]
    parts = [p for p in re.split(r"[^0-9a-zA-Z]+", ident) if p]
    if parts:
        ident = parts[0][:1].lower() + parts[0][1:] + "".join(
            p[:1].upper() + p[1:] for p in parts[1:]
        )
    if not ident:
        ident = "value"
    if ident[0].isdigit():
        ident = "n" + ident
    return f"`{ident}`" if ident in RESERVED else ident


def pascal(name: str) -> str:
    parts = [p for p in re.split(r"[^0-9a-zA-Z]+", name) if p]
    if not parts:
        return "Model"
    out = "".join(p[:1].upper() + p[1:] for p in parts)
    return "M" + out if out[0].isdigit() else out


def enum_const(value: str, index: int) -> str:
    cleaned = re.sub(r"[^0-9A-Za-z]+", "_", value).strip("_").upper()
    if not cleaned:
        cleaned = f"VALUE_{index}"
    if cleaned[0].isdigit():
        cleaned = "V_" + cleaned
    return f"`{cleaned}`" if cleaned in RESERVED else cleaned


class Generator:
    def __init__(self, spec: dict[str, Any], version: str) -> None:
        self.spec = spec
        self.version = version
        self.schemas: dict[str, Any] = spec.get("components", {}).get("schemas", {})
        self.used: set[str] = set()
        self.models: list[str] = []
        self.inline_done: set[str] = set()
        self.alias_only: set[str] = set()

    # ------------------------------------------------------------------ types
    def use(self, name: str) -> str:
        if name not in self.used:
            self.used.add(name)
            schema = self.schemas.get(name)
            if schema is None:
                raise SystemExit(f"spec references unknown schema {name!r}")
            self.emit_named(name, schema)
        return name

    def ktype(self, schema: dict[str, Any] | None, ctx: str, required: bool) -> str:
        if schema is None:
            return "JsonElement?"

        nullable = bool(schema.get("nullable"))

        if "$ref" in schema:
            base = self.use(schema["$ref"].rsplit("/", 1)[-1])
        elif "allOf" in schema:
            base, nullable = self.resolve_all_of(schema, ctx, nullable)
        elif schema.get("enum"):
            base = "String"
        elif schema.get("type") == "array":
            base = "List<" + self.ktype(schema.get("items") or {}, ctx + "Item", True) + ">"
        elif schema.get("type") == "object" and "properties" in schema:
            base = self.inline_object(schema, ctx)
        elif schema.get("type") == "object" and isinstance(schema.get("additionalProperties"), dict):
            value = self.ktype(schema["additionalProperties"], ctx + "Value", True)
            base = f"Map<String, {value}>"
        elif schema.get("type") == "object":
            base = "Map<String, JsonElement>"
        elif schema.get("type") == "string":
            base = "String"
        elif schema.get("type") == "integer":
            big = schema.get("format") == "int64" or (schema.get("maximum") or 0) > 2_147_483_647
            base = "Long" if big else "Int"
        elif schema.get("type") == "number":
            base = "Double"
        elif schema.get("type") == "boolean":
            base = "Boolean"
        else:
            base = "JsonElement?"

        needs_optional = not required and base != "JsonElement?"
        if (nullable or needs_optional) and not base.endswith("?"):
            base += "?"
        if base == "JsonElement?" and required:
            base = "JsonElement?"
        return base

    def resolve_all_of(self, schema: dict[str, Any], ctx: str, nullable: bool) -> tuple[str, bool]:
        branches = schema["allOf"]
        refs = [b for b in branches if "$ref" in b]
        objects = [b for b in branches if b.get("type") == "object" or "properties" in b]
        extra_nullable = any(b.get("nullable") for b in branches if "$ref" not in b and "properties" not in b)

        if len(refs) == 1 and not objects:
            return self.use(refs[0]["$ref"].rsplit("/", 1)[-1]), nullable or extra_nullable

        merged: dict[str, Any] = {"type": "object", "properties": {}, "required": []}
        nullable = nullable or extra_nullable
        for branch in branches:
            target = self.schemas.get(branch["$ref"].rsplit("/", 1)[-1]) if "$ref" in branch else branch
            if not isinstance(target, dict):
                continue
            merged["properties"].update(target.get("properties", {}))
            merged["required"].extend(target.get("required", []))
            if target.get("nullable"):
                nullable = True
        return self.inline_object(merged, ctx), nullable

    def inline_object(self, schema: dict[str, Any], ctx: str) -> str:
        name = pascal(ctx)
        if name not in self.inline_done:
            self.inline_done.add(name)
            self.emit_object(name, schema)
        return name

    # ---------------------------------------------------------------- emitters
    def emit_named(self, name: str, schema: dict[str, Any]) -> None:
        if schema.get("enum"):
            self.emit_enum(name, schema)
        elif "allOf" in schema:
            refs = [b for b in schema["allOf"] if "$ref" in b]
            objects = [b for b in schema["allOf"] if b.get("type") == "object" or "properties" in b]
            if len(refs) == 1 and not objects and not schema.get("nullable"):
                only_nullable = all(
                    b.get("nullable") for b in schema["allOf"] if "$ref" not in b
                )
                if only_nullable:
                    target = refs[0]["$ref"].rsplit("/", 1)[-1]
                    self.use(target)
                    self.models.append(f"typealias {name} = {target}?\n")
                    return
            resolved, nullable = self.resolve_all_of(schema, name, False)
            self.models.append(f"typealias {name} = {resolved}{'?' if nullable else ''}\n")
        elif schema.get("type") == "array":
            items = self.ktype(schema.get("items") or {}, name + "Item", True)
            self.models.append(f"typealias {name} = List<{items}>\n")
        elif schema.get("type") == "string" and not schema.get("properties"):
            self.models.append(f"typealias {name} = String\n")
        else:
            self.emit_object(name, schema)

    def emit_enum(self, name: str, schema: dict[str, Any]) -> None:
        self.models.append(f"typealias {name} = String\n")
        body = "\n".join(
            f'    const val {enum_const(v, i)}: String = "{v}"'
            for i, v in enumerate(schema["enum"])
        )
        self.models.append(
            f"/** Wire values for `{name}` (Immich {self.version}). */\n"
            f"object {name}Values {{\n{body}\n}}\n"
        )

    def emit_object(self, name: str, schema: dict[str, Any]) -> None:
        props: dict[str, Any] = {}
        required: set[str] = set()
        for branch in schema.get("allOf") or [schema]:
            target = self.schemas.get(branch["$ref"].rsplit("/", 1)[-1]) if "$ref" in branch else branch
            if not isinstance(target, dict):
                continue
            props.update(target.get("properties", {}))
            required.update(target.get("required", []))

        if not props:
            self.models.append(
                f"@Serializable\nclass {name}(\n"
                f"    val extra: JsonElement? = null,\n)\n"
            )
            return

        # Every property is a constructor parameter with a default: that makes the
        # DTOs constructible for request bodies (search filters) and copy()-able,
        # while still decoding resiliently when the server omits optional keys.
        lines: list[str] = []
        for raw, prop in props.items():
            prop_type = self.ktype(prop, f"{name}{pascal(raw)}", raw in required)
            ident = kotlin_ident(raw)
            default = self.default_for(prop_type)
            if default is None:
                prop_type = prop_type if prop_type.endswith("?") else prop_type + "?"
                default = "null"
            description = (prop.get("description") or "").strip().splitlines()
            if not description:
                description = [f"Wire field `{raw}`."]
            summary = description[0].replace("*/", "* /")[:120]
            annotations = ""
            if ident.strip("`") != raw:
                annotations = f'    @SerialName("{raw}")\n'
            lines.append(f"{annotations}    /** {summary} */")
            lines.append(f"    val {ident}: {prop_type} = {default},")
        body = "\n".join(lines)
        self.models.append(f"@Serializable\ndata class {name}(\n{body}\n)\n")

    @staticmethod
    def default_for(ktype: str) -> str | None:
        if ktype == "JsonElement?":
            return "null"
        if ktype.endswith("?"):
            return "null"
        if ktype == "String":
            return '""'
        if ktype == "Boolean":
            return "false"
        if ktype == "Int":
            return "0"
        if ktype == "Long":
            return "0L"
        if ktype == "Double":
            return "0.0"
        if ktype.startswith("List<"):
            return "emptyList()"
        if ktype.startswith("Map<"):
            return "emptyMap()"
        return None

    @staticmethod
    def serializer_expr(ktype: str) -> str:
        """Kotlin expression yielding a KSerializer<T> for a response type."""
        if ktype.startswith("List<"):
            inner = ktype[len("List<"):-1]
            return f"ListSerializer({inner}.serializer())"
        if ktype.startswith("Map<"):
            # Map responses are not used by this app's endpoints.
            raise SystemExit(f"unsupported Map response type: {ktype}")
        return f"{ktype}.serializer()"

    # -------------------------------------------------------------- operations
    def find_operation(self, op_id: str) -> dict[str, Any]:
        for path, item in self.spec.get("paths", {}).items():
            for method, op in item.items():
                if method in ("get", "post", "put", "delete", "patch") and op.get("operationId") == op_id:
                    return {"id": op_id, "path": path, "method": method.upper(), "op": op}
        raise SystemExit(f"operation {op_id!r} not present in spec")

    def success_schema(self, op: dict[str, Any]) -> dict[str, Any] | None:
        """First 2xx response with a JSON body (Immich uses 201 for create/login)."""
        responses = op.get("responses", {})
        for code in sorted(responses, key=lambda c: (str(c) != "200", str(c))):
            if not str(code).startswith("2"):
                continue
            content = (responses[code].get("content") or {}).get("application/json")
            if content and content.get("schema"):
                return content["schema"]
        return None

    def response_ktype(self, op: dict[str, Any]) -> str:
        schema = self.success_schema(op)
        if not schema:
            return "Unit"
        return self.ktype(schema, pascal(op["operationId"]) + "Response", True)

    def emit_endpoint(self, entry: dict[str, Any]) -> str:
        op = entry["op"]
        op_id = entry["id"]
        params = op.get("parameters", [])
        path_params = {p["name"]: p for p in params if p.get("in") == "path"}
        query_params = [p for p in params if p.get("in") == "query"]

        body_schema = (op.get("requestBody", {}).get("content", {}).get("application/json") or {}).get("schema")
        body_type = self.ktype(body_schema, pascal(op_id) + "Request", True) if body_schema else None

        args: list[str] = []
        path_expr_parts: list[str] = []
        for chunk in re.split(r"(\{[^}]+\})", entry["path"]):
            if chunk.startswith("{"):
                ident = param_ident(chunk[1:-1])
                args.append(f"{ident}: String")
                path_expr_parts.append(f"${{{ident}}}")
            else:
                path_expr_parts.append(chunk)
        path_expr = "".join(path_expr_parts)

        required_query: list[tuple[str, str]] = []
        optional_query: list[tuple[str, str]] = []
        for p in query_params:
            ident = param_ident(p["name"])
            qtype = self.ktype(p.get("schema"), pascal(op_id) + pascal(p["name"]), True)
            if p.get("required"):
                args.append(f"{ident}: {qtype}")
                required_query.append((p["name"], ident))
            else:
                qtype = qtype if qtype.endswith("?") else qtype + "?"
                optional_query.append((p["name"], ident))
                args.append(f"{ident}: {qtype} = null")

        if body_type:
            args.append(f"body: {body_type}")

        ret = self.response_ktype(entry["op"])
        serializer_expr = self.serializer_expr(ret)
        request_lines = [
            f'            ApiRequest("{entry["method"]}", "{path_expr}", mapOf(',
            *[f'                "{wire}" to {ident}.toString(),' for wire, ident in required_query],
            *[f'                "{wire}" to {ident}?.toString(),' for wire, ident in optional_query],
            "            )),",
        ]
        # Bodies are encoded here, not in the runtime, so the compiler enforces that
        # the right serializer is paired with the right DTO.
        body_lines = [f"            client.encodeBody({body_type}.serializer(), body),"] if body_type else []

        if ret == "Unit":
            lines = [
                f"    suspend fun {op_id}({', '.join(args)}) {{",
                "        client.executeUnit(",
                *request_lines,
                *body_lines,
                "        )",
                "    }",
            ]
            return "\n".join(lines) + "\n"

        lines = [
            f"    suspend fun {op_id}({', '.join(args)}): {ret} =",
            "        client.execute(",
            *request_lines,
            f"            {serializer_expr},",
            *body_lines,
            "        )",
        ]
        return "\n".join(lines) + "\n"

    def emit_binary_endpoint(self, entry: dict[str, Any]) -> str:
        op_id = entry["id"]
        params = entry["op"].get("parameters", [])
        args: list[str] = []
        path_expr_parts: list[str] = []
        for chunk in re.split(r"(\{[^}]+\})", entry["path"]):
            if chunk.startswith("{"):
                ident = param_ident(chunk[1:-1])
                args.append(f"{ident}: String")
                path_expr_parts.append(f"${{{ident}}}")
            else:
                path_expr_parts.append(chunk)
        path_expr = "".join(path_expr_parts)

        required_query: list[tuple[str, str]] = []
        optional_query: list[tuple[str, str]] = []
        for p in params:
            if p.get("in") != "query":
                continue
            ident = param_ident(p["name"])
            qtype = self.ktype(p.get("schema"), pascal(op_id) + pascal(p["name"]), True)
            if p.get("required"):
                args.append(f"{ident}: {qtype}")
                required_query.append((p["name"], ident))
            else:
                qtype = qtype if qtype.endswith("?") else qtype + "?"
                optional_query.append((p["name"], ident))
                args.append(f"{ident}: {qtype} = null")

        args.append("sink: java.io.File")
        args.append("signal: android.os.CancellationSignal? = null")
        args.append("onProgress: ((bytesWritten: Long) -> Unit)? = null")
        lines = [
            f"    suspend fun {op_id}({', '.join(args)}): Long =",
            "        client.download(",
            f'            ApiRequest("{entry["method"]}", "{path_expr}", mapOf(',
            *[f'                "{wire}" to {ident}.toString(),' for wire, ident in required_query],
            *[f'                "{wire}" to {ident}?.toString(),' for wire, ident in optional_query],
            "            )),",
            "            sink,",
            "            signal,",
            "            onProgress,",
            "        )",
        ]
        return "\n".join(lines) + "\n"


MODELS_HEADER = """// AUTO-GENERATED by tools/openapi/generate_models.py from the Immich {version} OpenAPI spec.
// DO NOT EDIT BY HAND.
//
// Regenerate after an Immich release:
//   python3 tools/openapi/generate_models.py --spec tools/openapi/immich-v{version}.json \\
//       --out app/src/main/kotlin/dev/immich/picker/api --version {version}
//
// Unknown JSON keys are ignored by the runtime Json instance, and all enums are
// modelled as String so new members in patch releases cannot break parsing.
@file:Suppress("unused", "PropertyName", "SpellCheckingInspection")

package dev.immich.picker.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
"""

ENDPOINTS_HEADER = """// AUTO-GENERATED by tools/openapi/generate_models.py from the Immich {version} OpenAPI spec.
// DO NOT EDIT BY HAND.
@file:Suppress("unused", "SpellCheckingInspection")

package dev.immich.picker.api

import dev.immich.picker.net.ApiRequest
import dev.immich.picker.net.ImmichApiClient
import kotlinx.serialization.builtins.ListSerializer
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    with open(args.spec, "r", encoding="utf-8") as handle:
        spec = json.load(handle)

    gen = Generator(spec, args.version)

    # Walk responses/bodies first so that dependencies are emitted before use order
    # does not matter (Kotlin is order independent within a file).
    json_ops = [gen.find_operation(o) for o in OPERATIONS]
    binary_ops = [gen.find_operation(o) for o in BINARY_OPERATIONS]

    endpoint_bodies = [gen.emit_endpoint(entry) for entry in json_ops]
    endpoint_bodies += [gen.emit_binary_endpoint(entry) for entry in binary_ops]

    os.makedirs(args.out, exist_ok=True)
    models_path = os.path.join(args.out, "Models.kt")
    endpoints_path = os.path.join(args.out, "Endpoints.kt")

    models = MODELS_HEADER.format(version=args.version) + "\n" + "\n".join(gen.models)
    endpoints = (
        ENDPOINTS_HEADER.format(version=args.version)
        + "\n/** Typed wrapper over the Immich endpoints used by this app. */\n"
        + "class ImmichEndpoints(private val client: ImmichApiClient) {\n\n"
        + "\n".join(endpoint_bodies)
        + "\n}\n"
    )

    with open(models_path, "w", encoding="utf-8") as handle:
        handle.write(models.rstrip() + "\n")
    with open(endpoints_path, "w", encoding="utf-8") as handle:
        handle.write(endpoints.rstrip() + "\n")

    print(f"wrote {models_path} ({len(gen.used)} schemas)")
    print(f"wrote {endpoints_path} ({len(json_ops) + len(binary_ops)} operations)")


if __name__ == "__main__":
    main()
