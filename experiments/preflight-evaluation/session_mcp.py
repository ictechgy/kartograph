#!/usr/bin/env python3
"""공통 읽기 도구와 실제 제품 MCP를 한 예산·기록 경계로 연결하는 실험용 proxy다."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import stat
import subprocess
import sys
import time

MAX_FRAME = 1024 * 1024
MAX_TRACE = 64 * 1024 * 1024
EXPECTED_PRODUCT_TOOLS = {"freshness", "impact", "query_symbol"}


def encode(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"))


class Session:
    def __init__(self, repository, trace, budget, product_command=None, source_hashes=None):
        self.root = repository.resolve(strict=True)
        self.trace = trace
        self.remaining = budget
        self.sequence = 0
        self.trace_bytes = 0
        self.product = None
        self.product_tools = {}
        tracked = subprocess.check_output(["git", "-C", str(self.root), "ls-files", "-z"]).decode("utf-8").split("\0")
        self.files = sorted(name for name in tracked if name and (
            Path(name).suffix in {".java", ".kt", ".kts", ".gradle"} or
            Path(name).name in {"pom.xml", "README.md", "LICENSE", "LICENSE.txt"}))
        self.allowed = set(self.files)
        if source_hashes is not None and (set(source_hashes) != self.allowed or
                any(not isinstance(value, str) or len(value) != 64 or
                    any(character not in "0123456789abcdef" for character in value) for value in source_hashes.values())):
            raise RuntimeError("fixed source manifest does not match tracked source surface")
        self.source_hashes = source_hashes
        self.root_fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
        if product_command:
            try:
                self.product = subprocess.Popen(product_command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                                stderr=subprocess.DEVNULL)
                initialized = self.product_rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                    "clientInfo": {"name": "kartograph-preflight-recorder", "version": "1"}})
                if "result" not in initialized:
                    raise RuntimeError("product initialization failed")
                self.product_send({"jsonrpc": "2.0", "method": "notifications/initialized"})
                listing = self.product_rpc("tools/list", {})
                tools = listing.get("result", {}).get("tools", [])
                if (not isinstance(tools, list) or any(not isinstance(tool, dict) or
                        not isinstance(tool.get("name"), str) for tool in tools)):
                    raise RuntimeError("product tool discovery failed")
                self.product_tools = {tool["name"]: tool for tool in tools}
                if set(self.product_tools) != EXPECTED_PRODUCT_TOOLS or len(self.product_tools) != len(tools):
                    raise RuntimeError("unexpected product tool surface")
            except Exception as error:
                self.close()
                raise RuntimeError("product startup failed") from error

    def record(self, channel, value):
        line = encode({"time": time.monotonic(), "channel": channel, "message": value}) + "\n"
        size = len(line.encode("utf-8"))
        if size > 2 * MAX_FRAME or self.trace_bytes + size > MAX_TRACE:
            raise RuntimeError("proxy trace exceeds bounded evidence limit")
        self.trace.write(line)
        self.trace.flush()
        self.trace_bytes += size

    def product_send(self, value):
        self.record("to_product", value)
        self.product.stdin.write((encode(value) + "\n").encode())
        self.product.stdin.flush()

    def product_rpc(self, method, params):
        self.sequence += 1
        identifier = "proxy-" + str(self.sequence)
        self.product_send({"jsonrpc": "2.0", "id": identifier, "method": method, "params": params})
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            ready, _, _ = select.select([self.product.stdout], [], [], max(0, deadline - time.monotonic()))
            if not ready:
                break
            line = self.product.stdout.readline(MAX_FRAME + 1)
            if not line or len(line) > MAX_FRAME:
                raise RuntimeError("product response unavailable or oversized")
            try:
                response = json.loads(line.decode("utf-8"))
            except (UnicodeError, json.JSONDecodeError) as error:
                raise RuntimeError("product response is malformed") from error
            if not isinstance(response, dict):
                raise RuntimeError("product response is malformed")
            self.record("from_product", response)
            if response.get("id") == identifier:
                return response
        raise RuntimeError("product response timed out")

    def read_file(self, name):
        if not isinstance(name, str) or name not in self.allowed:
            raise ValueError("file is not in the fixed source manifest")
        parts = Path(name).parts
        directory = os.dup(self.root_fd)
        source = None
        try:
            for part in parts[:-1]:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory)
                os.close(directory); directory = child
            source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=directory)
            metadata = os.fstat(source)
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > MAX_FRAME:
                raise ValueError("source file exceeds one MiB or is not regular")
            chunks, size = [], 0
            while True:
                chunk = os.read(source, min(65536, MAX_FRAME + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk); size += len(chunk)
                if size > MAX_FRAME:
                    raise ValueError("source file exceeds one MiB")
            raw = b''.join(chunks)
        finally:
            if source is not None:
                os.close(source)
            os.close(directory)
        observed = hashlib.sha256(raw).hexdigest()
        if self.source_hashes is not None and self.source_hashes.get(name) != observed:
            raise ValueError("source file changed after the fixed manifest")
        return raw.decode("utf-8"), observed

    @staticmethod
    def integer(args, name, default, minimum, maximum):
        value = args.get(name, default)
        if type(value) is not int or not minimum <= value <= maximum:
            raise ValueError(name + " is out of range")
        return value

    def source_call(self, name, args):
        fields = {"source_list": {"prefix", "offset", "limit"},
                  "source_read": {"file", "startLine", "limit"},
                  "source_search": {"text", "prefix", "offset", "limit"}}[name]
        if not isinstance(args, dict) or set(args) - fields:
            raise ValueError("unknown source tool argument")
        if name == "source_read":
            content, digest = self.read_file(args.get("file"))
            lines = content.splitlines()
            start = self.integer(args, "startLine", 1, 1, 1000000) - 1
            limit = self.integer(args, "limit", 120, 1, 200)
            selected = lines[start:start + limit]
            text = "\n".join(f"{start + i + 1}: {line}" for i, line in enumerate(selected))
            if len(text.encode()) > 65536:
                raise ValueError("read result exceeds64KiB; reduce line limit")
            return {"file": args["file"], "sha256": digest, "text": text, "totalLines": len(lines),
                    "hasNext": start + len(selected) < len(lines), "nextLine": start + len(selected) + 1}
        prefix = args.get("prefix", "")
        if not isinstance(prefix, str) or len(prefix) > 512 or prefix.startswith("/") or ".." in prefix.split("/"):
            raise ValueError("invalid source prefix")
        candidates = [name for name in self.files if name.startswith(prefix)]
        offset = self.integer(args, "offset", 0, 0, 1000000)
        limit = self.integer(args, "limit", 50, 1, 100)
        if name == "source_list":
            values = candidates
        else:
            needle = args.get("text")
            if not isinstance(needle, str) or not 1 <= len(needle) <= 256 or "\n" in needle:
                raise ValueError("search text must be a literal single line,1..256chars")
            selected = []
            total = 0
            for candidate in candidates:
                content, _ = self.read_file(candidate)
                for index, line in enumerate(content.splitlines()):
                    if needle in line:
                        if offset <= total < offset + limit:
                            selected.append({"file": candidate, "line": index + 1, "text": line[:500]})
                        total += 1
            return {"results": selected, "total": total, "hasNext": offset + limit < total, "nextOffset": offset + limit}
        return {"results": values[offset:offset + limit], "total": len(values),
                "hasNext": offset + limit < len(values), "nextOffset": offset + limit}

    def call(self, name, args):
        if self.remaining <= 0:
            return {"isError": True, "content": [{"type": "text", "text": "Shared tool budget exhausted; finish with the evidence already observed."}]}
        self.remaining -= 1
        if name in self.product_tools:
            try:
                response = self.product_rpc("tools/call", {"name": name, "arguments": args})
            except (RuntimeError, OSError) as error:
                self.record("proxy_error", {"kind": "product_transport", "message": "product tool transport failed"})
                return {"isError": True, "content": [{"type": "text", "text": "Product tool transport failed; finish with other observed evidence."}]}
            if "result" not in response:
                error = response.get("error")
                if not isinstance(error, dict):
                    self.record("proxy_error", {"kind": "product_protocol", "message": "product tool response failed"})
                    return {"isError": True, "content": [{"type": "text", "text": "Product tool response failed; finish with other observed evidence."}]}
                return {"isError": True, "content": [{"type": "text", "text": encode(error)}]}
            return response["result"]
        try:
            value = self.source_call(name, args)
            return {"content": [{"type": "text", "text": encode(value)}], "structuredContent": value}
        except (ValueError, OSError, UnicodeError, KeyError) as error:
            message = str(error) if isinstance(error, ValueError) else "source request failed"
            return {"isError": True, "content": [{"type": "text", "text": message}]}

    def close(self):
        product, self.product = self.product, None
        try:
            if product:
                try:
                    product.stdin.close()
                except (BrokenPipeError, OSError):
                    pass
                try:
                    product.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    try:
                        product.terminate()
                    except OSError:
                        pass
                    try:
                        product.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        try:
                            product.kill(); product.wait(timeout=5)
                        except (OSError, subprocess.TimeoutExpired):
                            pass
        finally:
            if product and product.stdout:
                try:
                    product.stdout.close()
                except OSError:
                    pass
            if getattr(self, "root_fd", None) is not None:
                try:
                    os.close(self.root_fd)
                except OSError:
                    pass
                self.root_fd = None


def schemas():
    string = {"type": "string"}
    integer = {"type": "integer"}
    definitions = [
        ("source_list", "List tracked Java/Kotlin source and build files. Prefix is literal; use offset/limit to page.",
         {"prefix": string, "offset": integer, "limit": integer}, []),
        ("source_read", "Read a tracked source file with1-based line numbers, at most200lines/64KiB. Use startLine to continue.",
         {"file": string, "startLine": integer, "limit": integer}, ["file"]),
        ("source_search", "Search a literal case-sensitive text in tracked source/build files. Prefix narrows paths; offset/limit pages matches.",
         {"text": string, "prefix": string, "offset": integer, "limit": integer}, ["text"]),
    ]
    return [{"name": name, "description": description, "inputSchema": {"type": "object", "properties": properties,
        "required": required, "additionalProperties": False}, "annotations": {"readOnlyHint": True, "destructiveHint": False,
        "idempotentHint": True, "openWorldHint": False}} for name, description, properties, required in definitions]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--trace", type=Path, required=True)
    parser.add_argument("--max-tools", type=int, default=12)
    parser.add_argument("--product-command", type=Path)
    parser.add_argument("--source-manifest", type=Path)
    args = parser.parse_args()
    session = None
    try:
        if args.max_tools != 12:
            raise RuntimeError("shared tool budget must be exactly12")
        if args.product_command and args.product_command.stat().st_size > MAX_FRAME:
            raise RuntimeError("fixed product command is oversized")
        command = json.loads(args.product_command.read_text()) if args.product_command else None
        if command is not None and (not isinstance(command, list) or not command or
                any(not isinstance(value, str) for value in command)):
            raise RuntimeError("invalid fixed product command")
        source_hashes = None
        if args.source_manifest:
            if args.source_manifest.stat().st_size > MAX_FRAME:
                raise RuntimeError("fixed source manifest is oversized")
            source_hashes = json.loads(args.source_manifest.read_text())
            if not isinstance(source_hashes, dict):
                raise RuntimeError("invalid fixed source manifest")
        with args.trace.open("x") as trace:
            session = Session(args.repository, trace, args.max_tools, command, source_hashes)
            initialized = False
            for raw in iter(lambda: sys.stdin.buffer.readline(262145), b""):
                if len(raw) > 262144:
                    raise ValueError("input frame too large")
                request = json.loads(raw.decode("utf-8"))
                session.record("from_client", request)
                identifier = request.get("id")
                if "id" not in request:
                    continue
                method = request.get("method")
                result = None
                error = None
                if method == "initialize":
                    initialized = True
                    result = {"protocolVersion": "2025-11-25", "capabilities": {"tools": {}},
                              "serverInfo": {"name": "preflight-recorder", "version": "1"}}
                elif method == "ping":
                    result = {}
                elif not initialized:
                    error = {"code": -32002, "message": "Initialize first"}
                elif method == "tools/list":
                    result = {"tools": schemas() + list(session.product_tools.values())}
                elif method == "tools/call":
                    params = request.get("params", {})
                    result = session.call(params.get("name"), params.get("arguments", {}))
                else:
                    error = {"code": -32601, "message": "Method not found"}
                response = {"jsonrpc": "2.0", "id": identifier, "error" if error else "result": error if error else result}
                if len(encode(response).encode("utf-8")) > MAX_FRAME:
                    response = {"jsonrpc": "2.0", "id": identifier,
                                "error": {"code": -32603, "message": "Bounded proxy response exceeded one MiB"}}
                session.record("to_client", response)
                print(encode(response), flush=True)
        return 0
    except Exception:
        print("error: preflight MCP proxy failed", file=sys.stderr)
        return 2
    finally:
        if session:
            session.close()


if __name__ == "__main__":
    raise SystemExit(main())
