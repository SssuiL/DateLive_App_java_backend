"""Read Python syntax without importing the legacy application or its settings."""
import argparse
import ast
import json
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--source", type=Path, default=Path("E:/python_workspace/DateLive_App"))
SOURCE = parser.parse_args().source.resolve()
OUTPUT = Path(__file__).resolve().parents[1] / "docs"


def parse(path):
    return ast.parse(path.read_text(encoding="utf-8-sig"))


def constant(node, default=None):
    try:
        return ast.literal_eval(node)
    except (ValueError, TypeError):
        return default


def location(path, node):
    return f"{path.relative_to(SOURCE).as_posix()}:{node.lineno}"


mounts = {}
for node in ast.walk(parse(SOURCE / "app/api/router.py")):
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == "include_router":
        prefix = next((constant(k.value, "") for k in node.keywords if k.arg == "prefix"), "")
        mounts.setdefault(ast.unparse(node.args[0]), []).append(prefix)
# create_app mounts the storage router a second time for legacy URL compatibility.
mounts.setdefault("storage_assets.router", []).append("/uploads")

routes = []
for path in sorted((SOURCE / "app/api").glob("*.py")):
    for node in ast.walk(parse(path)):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for decorator in node.decorator_list:
            if not isinstance(decorator, ast.Call) or not isinstance(decorator.func, ast.Attribute):
                continue
            method = decorator.func.attr
            if method not in {"get", "post", "put", "patch", "delete", "head", "options", "websocket", "api_route"}:
                continue
            key = f"{path.stem}.{ast.unparse(decorator.func.value)}"
            prefixes = mounts.get(key)
            if prefixes is None:
                raise RuntimeError(f"Unresolved router mount: {key}")
            suffix = constant(decorator.args[0]) if decorator.args else next((constant(k.value) for k in decorator.keywords if k.arg == "path"), None)
            if not isinstance(suffix, str):
                raise RuntimeError(f"Unresolved route: {location(path, node)}")
            methods = [method.upper()]
            if method == "api_route":
                methods = next(constant(k.value) for k in decorator.keywords if k.arg == "methods")
            for prefix in prefixes:
                for verb in methods:
                    routes.append({"method": verb, "path": prefix + suffix, "source": location(path, node), "handler": node.name, "status": "pending"})

tables = []
for path in sorted((SOURCE / "app/models").glob("*.py")):
    for node in ast.walk(parse(path)):
        if isinstance(node, ast.ClassDef):
            for member in node.body:
                if isinstance(member, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "__tablename__" for t in member.targets):
                    tables.append({"table": constant(member.value), "model": node.name, "source": location(path, node), "owner": "pending"})

tests = []
for path in sorted((SOURCE / "tests").glob("test_*.py")):
    tests.append({"file": path.relative_to(SOURCE).as_posix(), "test_definitions": sum(isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)) and n.name.startswith("test_") for n in ast.walk(parse(path)))})

jobs = []
path = SOURCE / "app/main.py"
for node in ast.walk(parse(path)):
    if isinstance(node, ast.Call) and ast.unparse(node.func) == "asyncio.create_task":
        jobs.append({"call": ast.unparse(node.args[0]), "source": location(path, node)})

commit = subprocess.check_output(["git", "-c", f"safe.directory={SOURCE.resolve().as_posix()}", "-C", str(SOURCE), "rev-parse", "HEAD"], text=True).strip()
duplicates = len(routes) - len({(r["method"], r["path"]) for r in routes})
if duplicates:
    raise RuntimeError(f"Duplicate mounted route signatures: {duplicates}")
summary = {"legacy_commit": commit, "http_routes": sum(r["method"] != "WEBSOCKET" for r in routes), "websocket_routes": sum(r["method"] == "WEBSOCKET" for r in routes), "tables": len(tables), "test_files": len(tests), "test_definitions": sum(t["test_definitions"] for t in tests), "migration_files": len(list((SOURCE / "alembic/versions").glob("*.py"))), "scheduled_loops": len(jobs)}
data = {"summary": summary, "limitations": ["Static source inventory, not runtime OpenAPI or production verification.", "Test definitions exclude parameterized expansion; tests were not executed by this tool.", "Gateway-only health routes, generated docs routes, response schemas and event payloads require separate contract capture.", "Scheduled loops exclude the separate media worker and externally launched operations scripts."], "routes": routes, "tables": tables, "tests": tests, "scheduled_loops": jobs}
OUTPUT.mkdir(parents=True, exist_ok=True)
(OUTPUT / "legacy-inventory.json").write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
lines = ["# 现有接口迁移清单", "", f"代码基线：`{commit}`。静态扫描结果，尚未迁移。", "", "包括 `/uploads` 兼容路径；独立网关健康接口和运行时契约另行补齐。", "", "| 方法 | 路径 | 实现位置 | 状态 |", "| --- | --- | --- | --- |"]
lines += [f"| {r['method']} | `{r['path']}` | `{r['source']}` | 待迁移 |" for r in routes]
(OUTPUT / "接口迁移清单.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
