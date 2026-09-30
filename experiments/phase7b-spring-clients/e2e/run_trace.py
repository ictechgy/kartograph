#!/usr/bin/env python3
"""두 서비스 e2e: 서비스 B의 route → 서비스 A의 RestClient 호출 지점 → 서비스 A의 핸들러·route 체인을 isthmus trace로 확인한다.

사용법:
    python3 experiments/phase7b-spring-clients/e2e/run_trace.py --isthmus <isthmus dist/cli/main.js> [--record]

1. 두 합성 Spring Boot 서비스(`users-service` = B, `orders-service` = A)를 Gradle로 컴파일한다(Maven Central 필요).
2. 서비스마다 `kartograph snapshot`, `routes --role server`를 실행하고 A는 `routes --role client`도 실행한다.
3. B는 route-decl 핸들러 usr 전체를 root로 `reach`(정방향), A는 route-call usr 전체를 root로
   `impact --format language-traversal`(역방향)을 실행한다.
4. 문서의 `project`를 합성 경로(`/e2e/<service>`)로 바꿔 기계와 무관한 입력을 만들고, link `orders->users`
   (`match.hosts: ["users.internal:8081"]`)를 선언한 workspace trace context를 쓴다.
5. `isthmus trace`로 B의 `GET /api/users/{}`를 선택해 기대 체인을 확인한다. `--record`면 입력과 출력을
   `recorded/`에 쓴다. 기록한 context는 그 디렉터리에서 `isthmus trace recorded/trace.context.json`으로 다시 실행할 수 있다.
"""

import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[2]
RECORDED = HERE / "recorded"
KARTOGRAPH = REPOSITORY / "cli" / "build" / "install" / "kartograph" / "bin" / "kartograph"
GENERATED_AT = "2026-01-01T00:00:00Z"
SERVICES = {"users": HERE / "users-service", "orders": HERE / "orders-service"}
HOST = "users.internal:8081"
TIMEOUT_SECONDS = 1800


def run(arguments, output=None):
    """명령을 실행하고 실패하면 원인과 함께 멈춘다.

    :param arguments: 명령 목록
    :param output: 표준 출력을 쓸 파일(선택)
    """
    completed = subprocess.run(arguments, capture_output=True, text=True, timeout=TIMEOUT_SECONDS, check=False)
    if completed.returncode != 0:
        raise SystemExit(f"{Path(arguments[0]).name} {arguments[1]} exited with {completed.returncode}: "
                         f"{completed.stderr.strip()[-2000:]}")
    if output is not None:
        Path(output).write_text(completed.stdout, encoding="utf-8")


def kartograph(*arguments):
    """설치한 kartograph CLI 명령 목록이다(`./gradlew :cli:installDist`가 먼저 필요하다)."""
    return [str(KARTOGRAPH), *arguments]


def documents(out):
    """서비스별 snapshot·http 문서·순회 문서를 만든다.

    :param out: 산출물 디렉터리
    """
    run([str(REPOSITORY / "gradlew"), "--no-daemon", "-q", "-p", str(HERE), "classes"])
    for name, directory in SERVICES.items():
        project = str(directory.resolve())
        graph = out / f"{name}.graph.json"
        run(kartograph("snapshot", "--classes", str(directory / "build" / "classes" / "kotlin" / "main"), "--project", project,
                       "--include-paths"), graph)
        run(kartograph("routes", "--role", "server", "--project", project, "--graph-file", str(graph)), out / f"{name}.server.http.json")
    orders = str(SERVICES["orders"].resolve())
    users = str(SERVICES["users"].resolve())
    run(kartograph("routes", "--role", "client", "--project", orders, "--graph-file", str(out / "orders.graph.json")),
        out / "orders.client.http.json")
    run(kartograph("reach", "--roots-from", str(out / "users.server.http.json"), "--graph-file", str(out / "users.graph.json"),
                   "--project", users, "--revision", "e2e-users", "--generated-at", GENERATED_AT), out / "users-forward.json")
    run(kartograph("impact", "--format", "language-traversal", "--roots-from", str(out / "orders.client.http.json"),
                   "--graph-file", str(out / "orders.graph.json"), "--project", orders, "--revision", "e2e-orders",
                   "--generated-at", GENERATED_AT), out / "orders-reverse.json")
    for name in ("users", "orders"):
        for suffix in ("server.http", "client.http", "forward", "reverse"):
            path = out / (f"{name}.{suffix}.json" if "." in suffix else f"{name}-{suffix}.json")
            if path.is_file():
                normalize(path, f"/e2e/{name}-service")


def normalize(path, project):
    """문서의 `project`·`generatedAt`을 합성 값으로 바꿔 기계와 무관하게 만든다.

    :param path: 문서 경로
    :param project: 합성 project 경로
    """
    document = json.loads(path.read_text(encoding="utf-8"))
    document["project"] = project
    if "generatedAt" in document:
        document["generatedAt"] = GENERATED_AT
    if "sourceModifiedAt" in document:
        document["sourceModifiedAt"] = GENERATED_AT
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def context():
    """workspace trace context다 — B의 route를 선택하고 A → B link를 host로 귀속한다."""
    return {
        "format": "isthmus-trace-context",
        "version": 1,
        "members": [
            {"name": "users", "project": "/e2e/users-service", "revision": "e2e-users", "documents": ["users.server.http.json"],
             "analyses": [{"id": "users-forward", "platform": "kotlin", "role": "forward", "path": "users-forward.json"}]},
            {"name": "orders", "project": "/e2e/orders-service", "revision": "e2e-orders",
             "documents": ["orders.server.http.json", "orders.client.http.json"],
             "analyses": [{"id": "orders-reverse", "platform": "kotlin", "role": "reverse", "path": "orders-reverse.json"}]},
        ],
        "links": [{"name": "orders->users", "client": "orders", "server": "users", "match": {"hosts": [HOST]}}],
        "selection": {"routes": [{"method": "GET", "template": "/api/users/{}"}]},
    }


# 기대 체인의 JVM 신원이다.
USERS_HANDLER = "method:dev/kartograph/e2e/users/UsersController#user(Ljava/lang/String;)Ljava/lang/String;"
USERS_DOMAIN = "method:dev/kartograph/e2e/users/UserDirectory#find(Ljava/lang/String;)Ljava/lang/String;"
ORDERS_CALL = "method:dev/kartograph/e2e/orders/UserGateway#fetchUser(Ljava/lang/String;)Ljava/lang/String;"
ORDERS_SERVICE = "method:dev/kartograph/e2e/orders/OrderService#describe(Ljava/lang/String;)Ljava/lang/String;"
ORDERS_HANDLER = "method:dev/kartograph/e2e/orders/OrdersController#order(Ljava/lang/String;)Ljava/lang/String;"


def check(trace, out):
    """B route → A 호출 → A 핸들러(= A route의 핸들러) 체인을 확인한다.

    trace는 B의 route에 귀속된 A의 호출과 그 호출 지점에서 역방향으로 닿은 A의 심볼을 싣는다. A 자신의 route는 A가 server인 link가
    없어 trace가 잇지 않으므로(`http-member-unlinked`), A route의 핸들러 usr가 역방향 도달에 있는지로 확인한다. B 핸들러의 정방향
    도달(도메인 계층)은 reach 문서로 확인한다 — trace는 정방향 도달을 DB hop에만 쓴다.

    :param trace: trace 문서
    :param out: 산출물 디렉터리
    :returns: 문제 목록(비었으면 통과)
    """
    problems = []
    chain = trace["chains"][0] if trace.get("chains") else {}
    handlers = [handler["usr"] for handler in chain.get("handlers", [])]
    calls = [call for route in chain.get("routes", []) for call in route.get("calls", [])]
    if handlers != [USERS_HANDLER]:
        problems.append(f"B handler: {handlers}")
    if [call["call"]["symbol"].get("usr") for call in calls] != [ORDERS_CALL] or calls[0]["quality"] != "exact":
        problems.append(f"A call: {[(call['call']['symbol'], call['quality']) for call in calls]}")
    affected = [symbol["usr"] for call in calls for symbol in call.get("affected", [])]
    if affected != [ORDERS_SERVICE, ORDERS_HANDLER]:
        problems.append(f"A reverse chain: {affected}")
    orders_routes = json.loads((out / "orders.server.http.json").read_text(encoding="utf-8"))["facts"]
    if [(fact["method"], fact["channel"], fact["symbol"]["usr"]) for fact in orders_routes] != [("GET", "/orders/{}", ORDERS_HANDLER)]:
        problems.append(f"A route handler: {orders_routes}")
    if USERS_DOMAIN not in (out / "users-forward.json").read_text(encoding="utf-8"):
        problems.append("B handler forward reach misses the domain layer")
    return problems


def main():
    """전체 체인을 실행하고 기대 경로를 확인한다."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--isthmus", required=True)
    parser.add_argument("--record", action="store_true")
    arguments = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="spring-e2e-") as directory:
        out = Path(directory)
        documents(out)
        (out / "trace.context.json").write_text(json.dumps(context(), indent=2, sort_keys=True) + "\n", encoding="utf-8")
        run(["node", arguments.isthmus, "trace", str(out / "trace.context.json")], out / "trace.json")
        trace = json.loads((out / "trace.json").read_text(encoding="utf-8"))
        problems = check(trace, out)
        if arguments.record:
            RECORDED.mkdir(exist_ok=True)
            names = ["users.server.http.json", "users-forward.json", "orders.server.http.json", "orders.client.http.json",
                     "orders-reverse.json", "trace.context.json", "trace.json"]
            for name in names:
                (RECORDED / name).write_text((out / name).read_text(encoding="utf-8"), encoding="utf-8")
            print("recorded documents, context and trace")
    for problem in problems:
        print(f"MISMATCH: {problem}")
    print("B route -> A call -> A handler chain matched" if not problems else f"{len(problems)} mismatches")
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
