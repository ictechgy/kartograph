#!/usr/bin/env python3
"""actuator /mappings(oracle)와 kartograph route-decl 문서를 비교한다.

사용: compare_mappings.py <mappings.json> <routes.json> [--context-path /x]
- oracle은 프로젝트 핸들러 메서드(handlerMethod가 있고 className이 프레임워크 패키지가 아닌 것)만 센다.
- oracle 패턴은 독립 구현으로 정규화한다: {name}/{name:re}·세그먼트 전체 * -> {}, 끝 /** 또는 /{*x} -> /{**} (+ 접두사 펼침).
- 키: (method, template, 핸들러). 핸들러는 usr가 있으면 usr, 없으면 qualifiedName으로 비교한다.
"""
import json
import re
import sys


def oracle_entries(mappings, framework_prefixes):
    out = []
    for ctx in mappings["contexts"].values():
        m = ctx["mappings"]
        groups = []
        if "dispatcherServlets" in m:
            groups += [e for lst in m["dispatcherServlets"].values() for e in lst]
        if "dispatcherHandlers" in m:
            groups += [e for lst in m["dispatcherHandlers"].values() for e in lst]
        for e in groups:
            det = e.get("details") or {}
            hm = det.get("handlerMethod")
            rmc = det.get("requestMappingConditions")
            if not hm or not rmc:
                continue
            if any(hm["className"].startswith(p) for p in framework_prefixes):
                continue
            out.append((hm, rmc))
    return out


def canonical(pattern):
    if pattern in ("", "/"):
        return ["/"]
    segs = pattern.lstrip("/").split("/")
    res = []
    for i, s in enumerate(segs):
        last = i == len(segs) - 1
        if last and (s == "**" or re.fullmatch(r"\{\*[^}]+\}", s)):
            prefix = "/" + "/".join(res)
            return [prefix + "/{**}" if res else "/{**}", prefix]
        s = "{}" if s == "*" else re.sub(r"\{[^{}:]+(:(?:[^{}]|\{[^{}]*\})+)?\}", "{}", s)
        res.append(s)
    return ["/" + "/".join(res)]


def main():
    mappings = json.load(open(sys.argv[1]))
    routes = json.load(open(sys.argv[2]))
    ctx = ""
    prefixes = ["org.springframework.boot.", "org.springframework.web.", "org.springframework.data.", "org.springdoc."]
    args = sys.argv[3:]
    for i, a in enumerate(args):
        if a == "--context-path":
            ctx = args[i + 1]
    oracle = set()
    for hm, rmc in oracle_entries(mappings, prefixes):
        internal = hm["className"].replace(".", "/")
        usr = f"method:{internal}#{hm['name']}{hm['descriptor']}"
        qn = hm["className"] + "." + hm["name"]
        methods = rmc["methods"] or ["ANY"]
        for p in rmc["patterns"]:
            for t in canonical(ctx + p if p else (ctx or "/")):
                for m in methods:
                    oracle.add((m, t, usr, qn))
    use_usr = all(f.get("symbol", {}).get("usr") for f in routes["facts"])
    key = (lambda m, t, usr, qn: (m, t, usr)) if use_usr else (lambda m, t, usr, qn: (m, t, qn))
    oracle_keys = {key(*o) for o in oracle}
    static = [f for f in routes["facts"] if not f["dynamic"]]
    dynamic = [f for f in routes["facts"] if f["dynamic"]]
    fact_keys = {key(f["method"], f["channel"], f["symbol"].get("usr"), f["symbol"]["qualifiedName"]) for f in static}
    true_pos = fact_keys & oracle_keys
    false_pos = fact_keys - oracle_keys
    missed = oracle_keys - fact_keys
    server_limits = [l for l in routes["limitations"] if l.split(":")[0] + ":" in (
        "route-coverage:", "unresolved-route-prefix:", "route-framework-version-unknown:", "framework-provided-routes:",
        "route-dispatch-order-unknown:", "route-template-expansion-capped:")]
    root_static = [f for f in static if f["pathAnchor"] == "root" and not f.get("testSource")]
    judgeable_now = len(root_static) if not server_limits else 0
    total = len(routes["facts"]) or 1
    print(json.dumps({
        "identity": "usr" if use_usr else "qualifiedName",
        "facts": len(routes["facts"]), "staticFactKeys": len(fact_keys), "dynamicFacts": len(dynamic),
        "oracleKeys": len(oracle_keys),
        "precision": f"{len(true_pos)}/{len(fact_keys)}", "recall": f"{len(true_pos)}/{len(oracle_keys)}",
        "falsePositives": sorted(map(list, false_pos)), "missed": sorted(map(list, missed)),
        "serverSideLimitations": server_limits,
        "errorJudgeableNow": f"{judgeable_now}/{total}",
        "errorJudgeableIfScoped": f"{len(root_static)}/{total}",
    }, indent=2))


if __name__ == "__main__":
    main()
