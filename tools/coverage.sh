#!/usr/bin/env bash
# 全工程覆盖率：把各模块的 jacoco.exec 合并后按"代码归属模块"汇总。
# 为什么需要它：JaCoCo 的模块级报告只能统计本模块的测试，application/shell 没有自己的
# 测试类，其覆盖数据在 boot 的 exec 里，必须合并后再按包名归属。
set -euo pipefail
cd "$(dirname "$0")/.."

mvn -q test
JAR=$(ls ~/.m2/repository/org/jacoco/org.jacoco.cli/*/org.jacoco.cli-*-nodeps.jar | tail -1)
rm -rf target/coverage && mkdir -p target/coverage

EXECS=()
for module in jellyfish-domain jellyfish-application jellyfish-infrastructure jellyfish-shell jellyfish-boot; do
  [ -f "$module/target/jacoco.exec" ] && EXECS+=("$module/target/jacoco.exec")
done

java -jar "$JAR" report "${EXECS[@]}" \
  --classfiles jellyfish-domain/target/classes \
  --classfiles jellyfish-application/target/classes \
  --classfiles jellyfish-infrastructure/target/classes \
  --classfiles jellyfish-shell/target/classes \
  --classfiles jellyfish-di/target/classes \
  --classfiles jellyfish-boot/target/classes \
  --sourcefiles jellyfish-domain/src/main/java \
  --sourcefiles jellyfish-application/src/main/java \
  --sourcefiles jellyfish-infrastructure/src/main/java \
  --sourcefiles jellyfish-shell/src/main/java \
  --sourcefiles jellyfish-di/src/main/java \
  --sourcefiles jellyfish-boot/src/main/java \
  --html target/coverage --csv target/coverage/jacoco.csv >/dev/null

python3 - <<'PY'
import csv, collections
rows = list(csv.DictReader(open("target/coverage/jacoco.csv")))
per = collections.defaultdict(lambda: [0, 0, 0, 0])
for r in rows:
    pkg = r["PACKAGE"]
    mod = ("domain" if ".domain." in pkg else "application" if ".application." in pkg else
           "infrastructure" if ".infrastructure." in pkg else "shell" if ".shell." in pkg else
           "di" if pkg == "io.github.zcd0831.jellyfish.di" else "boot")
    v = per[mod]
    v[0] += int(r["INSTRUCTION_COVERED"]); v[1] += int(r["INSTRUCTION_MISSED"])
    v[2] += int(r["BRANCH_COVERED"]); v[3] += int(r["BRANCH_MISSED"])

def pct(c, m):
    t = c + m
    return 100.0 * c / t if t else 0.0

print("%-16s %8s %22s %8s" % ("模块", "指令覆盖", "", "分支覆盖"))
for k in ["domain", "application", "infrastructure", "shell", "di", "boot"]:
    ic, im, bc, bm = per.get(k, [0, 0, 0, 0])
    print("%-16s %7.1f%% (%d/%d) %9.1f%%" % (k, pct(ic, im), ic, ic + im, pct(bc, bm)))
ic = sum(v[0] for v in per.values()); im = sum(v[1] for v in per.values())
bc = sum(v[2] for v in per.values()); bm = sum(v[3] for v in per.values())
print("\n全工程：指令 %.1f%% (%d/%d)  分支 %.1f%% (%d/%d)" % (pct(ic, im), ic, ic + im, pct(bc, bm), bc, bc + bm))
PY
echo "HTML 报告：target/coverage/index.html"
