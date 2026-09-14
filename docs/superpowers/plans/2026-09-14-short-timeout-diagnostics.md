# 短超时诊断门禁实施计划

> **供执行代理使用：** 必须使用 `superpowers:subagent-driven-development`，逐任务执行本计划；
> 所有步骤使用复选框跟踪。

**目标：** 在不修改 Fibra 产品代码、公共 API、版本或依赖的前提下，证明短时挂起能够在截止前留下
进程树与 JVM 线程栈、按期清理全部受管进程，并限制真实 TTY 失败输出的体积。

**架构：** 复用 `scripts/run-ci-with-jvm-diagnostics.sh` 已有的时间参数、进程组清理和 JVM dump，不新增
诊断框架。新增一个 Java 21 source-file 挂起夹具和一个 Bash 自验脚本，在 CI 全量构建前独立运行；
`verify-cli-tty.py` 只增加纯格式化函数，把现有完整 buffer 改为固定尾部。

**技术栈：** Bash、Python 3 标准库、Java 21 source-file 模式、GitHub Actions。

---

## 文件边界

- 新建 `verification/ci/HangingJvmFixture.java`：只负责创建可识别的挂起 JVM 和一个忽略 `TERM` 的子进程。
- 新建 `scripts/verify-short-timeout-diagnostics.sh`：只负责黑盒验证现有诊断包装器的超时、取证、清理和
  正常退出码透传。
- 新建 `verification/ci/test_verify_cli_tty.py`：只验证 TTY 诊断尾部格式，不引入测试依赖。
- 修改 `verification/distribution/verify-cli-tty.py`：只限制错误消息中的输出体积，不改变 PTY、信号或
  10 秒截止行为。
- 修改 `.github/workflows/ci.yml`：只增加独立短超时门禁步骤；保留全量诊断默认 180 秒首采样。

明确不修改 `scripts/run-ci-with-jvm-diagnostics.sh`、`verify-archive.sh`、任何 Maven POM 或产品模块。

### 任务 1：用测试锁定 TTY 诊断尾部格式

**文件：**

- 新建：`verification/ci/test_verify_cli_tty.py`
- 修改：`verification/distribution/verify-cli-tty.py:14-15,43-102,160-197`

- [x] **步骤 1：先新增失败测试**

创建 `verification/ci/test_verify_cli_tty.py`：

```python
#!/usr/bin/env python3
import importlib.util
import pathlib
import unittest


MODULE_PATH = (pathlib.Path(__file__).resolve().parents[1]
               / "distribution" / "verify-cli-tty.py")
SPEC = importlib.util.spec_from_file_location("verify_cli_tty", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DiagnosticTailTest(unittest.TestCase):
    def test_reports_total_and_omitted_bytes_with_a_fixed_tail(self):
        self.assertEqual(
            "bytes=6 omitted=2 tail=b'cdef'",
            MODULE.diagnostic_tail(b"abcdef", limit=4))

    def test_keeps_short_output_without_claiming_omission(self):
        self.assertEqual(
            "bytes=3 omitted=0 tail=b'abc'",
            MODULE.diagnostic_tail(bytearray(b"abc"), limit=4))


if __name__ == "__main__":
    unittest.main()
```

- [x] **步骤 2：运行测试并确认因缺少函数而失败**

运行：

```bash
python3 verification/ci/test_verify_cli_tty.py
```

预期：失败，错误指出 `verify_cli_tty` 没有 `diagnostic_tail`；不得是导入或路径错误。

- [x] **步骤 3：实现最小纯函数**

在 `ANSI` 常量之后增加：

```python
DIAGNOSTIC_TAIL_BYTES = 16 * 1024


def diagnostic_tail(value, limit=DIAGNOSTIC_TAIL_BYTES):
    data = bytes(value)
    tail = data[-limit:] if limit else b""
    omitted = len(data) - len(tail)
    return f"bytes={len(data)} omitted={omitted} tail={tail!r}"
```

把所有异常中的完整 buffer 替换为尾部描述：

```python
f"stdout={diagnostic_tail(self.stdout)}\n"
f"terminal={diagnostic_tail(self.terminal)}"
```

屏幕摘要校验失败处同样改为：

```python
raise AssertionError(f"renderer 未收到完整解码输入：{diagnostic_tail(summary)}")
```

其余包含 `summary`、`stdout` 或 `terminal` 的失败消息采用同一函数；不得改变成功路径和 PTY 时序。

- [x] **步骤 4：运行定向测试和语法检查**

运行：

```bash
python3 verification/ci/test_verify_cli_tty.py
python3 -c 'import ast, pathlib; ast.parse(pathlib.Path("verification/distribution/verify-cli-tty.py").read_text())'
```

预期：2 个单元测试通过；AST 解析退出码为 0。

- [x] **步骤 5：提交这一独立行为**

```bash
git add verification/ci/test_verify_cli_tty.py \
  verification/distribution/verify-cli-tty.py
git commit -m "test(ci): bound tty timeout diagnostics"
```

### 任务 2：建立诊断包装器的确定性短超时自验

**文件：**

- 新建：`scripts/verify-short-timeout-diagnostics.sh`
- 新建：`verification/ci/HangingJvmFixture.java`
- 复用：`scripts/run-ci-with-jvm-diagnostics.sh`

- [ ] **步骤 1：先写会因缺少夹具而失败的门禁脚本**

创建并赋予可执行权限 `scripts/verify-short-timeout-diagnostics.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd)"
diagnostics_root="${FIBRA_CI_DIAGNOSTICS_DIR:?FIBRA_CI_DIAGNOSTICS_DIR must be set}"
fixture="$repository_root/verification/ci/HangingJvmFixture.java"
wrapper="$repository_root/scripts/run-ci-with-jvm-diagnostics.sh"
temporary_root="$(mktemp -d)"
pid_file="$temporary_root/pids.txt"
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
timeout_dir="$diagnostics_root/short-timeout-$run_id"
normal_dir="$diagnostics_root/normal-exit-$run_id"

cleanup() {
  while IFS= read -r pid; do
    [[ -n "$pid" ]] && kill -KILL "$pid" 2>/dev/null || true
  done < "$pid_file" 2>/dev/null || true
  rm -rf "$temporary_root"
}
trap cleanup EXIT

fail() {
  echo "$1" >&2
  exit 1
}

wait_for_pid_file() {
  local attempt
  for attempt in {1..50}; do
    [[ -s "$pid_file" ]] && return
    sleep 0.1
  done
  fail "挂起 fixture 未写入 PID"
}

assert_gone() {
  local pid="$1" attempt
  for attempt in {1..30}; do
    kill -0 "$pid" 2>/dev/null || return
    sleep 0.1
  done
  fail "短超时清理后仍存在进程 $pid"
}

mkdir -p "$diagnostics_root"
set +e
FIBRA_CI_DIAGNOSTICS_DIR="$timeout_dir" \
FIBRA_CI_DIAGNOSTICS_INITIAL_DELAY_SECONDS=1 \
FIBRA_CI_DIAGNOSTICS_INTERVAL_SECONDS=1 \
FIBRA_CI_COMMAND_TIMEOUT_SECONDS=2 \
FIBRA_CI_TERMINATION_GRACE_SECONDS=1 \
  "$wrapper" java "$fixture" "$pid_file"
timeout_status=$?
set -e

[[ "$timeout_status" -eq 124 ]] || fail "短超时门禁退出码不是 124：$timeout_status"
[[ -s "$timeout_dir/command-timeout.txt" ]] || fail "短超时门禁缺少 timeout marker"
sample_dir="$(find "$timeout_dir" -mindepth 1 -maxdepth 1 -type d | sort | head -n 1)"
[[ -n "$sample_dir" && -s "$sample_dir/processes.txt" ]] || fail "短超时门禁缺少进程快照"
jvm_dump="$(find "$sample_dir" -type f -name 'jvm-*.txt' -size +0c | head -n 1)"
[[ -n "$jvm_dump" ]] || fail "短超时门禁缺少 JVM dump"
grep -q 'fibra-short-timeout-fixture' "$jvm_dump" || fail "JVM dump 未识别挂起 fixture"

wait_for_pid_file
while IFS= read -r pid; do
  [[ -n "$pid" ]] && assert_gone "$pid"
done < "$pid_file"

set +e
FIBRA_CI_DIAGNOSTICS_DIR="$normal_dir" \
FIBRA_CI_DIAGNOSTICS_INITIAL_DELAY_SECONDS=1 \
  "$wrapper" sh -c 'exit 7'
normal_status=$?
set -e
[[ "$normal_status" -eq 7 ]] || fail "正常命令退出码未透传：$normal_status"
[[ ! -e "$normal_dir/command-timeout.txt" ]] || fail "正常命令产生了 timeout marker"

echo "短超时诊断门禁验证通过"
```

执行：

```bash
chmod 0755 scripts/verify-short-timeout-diagnostics.sh
diagnostics_dir="$(mktemp -d)"
FIBRA_CI_DIAGNOSTICS_DIR="$diagnostics_dir" \
  scripts/verify-short-timeout-diagnostics.sh
```

预期：失败，明确指出 `HangingJvmFixture.java` 不存在或无法启动；不得先修改诊断包装器来迁就测试。

- [ ] **步骤 2：增加最小挂起 JVM fixture**

创建 `verification/ci/HangingJvmFixture.java`：

```java
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public final class HangingJvmFixture {
    private HangingJvmFixture() {
    }

    public static void main(String[] arguments) throws Exception {
        Thread.currentThread().setName("fibra-short-timeout-fixture");
        var child = new ProcessBuilder("sh", "-c",
            "trap '' TERM; while :; do sleep 1; done").start();
        Files.writeString(Path.of(arguments[0]),
            ProcessHandle.current().pid() + "\n" + child.pid() + "\n");
        Thread.sleep(Duration.ofMinutes(5));
    }
}
```

fixture 不增加 shutdown hook：它的职责是让包装器证明 `TERM` 宽限和进程组 `KILL` 能清理完整受管树。

- [ ] **步骤 3：运行自验并确认超时、取证和清理全部通过**

```bash
bash -n scripts/run-ci-with-jvm-diagnostics.sh \
  scripts/verify-short-timeout-diagnostics.sh
diagnostics_dir="$(mktemp -d)"
FIBRA_CI_DIAGNOSTICS_DIR="$diagnostics_dir" \
  scripts/verify-short-timeout-diagnostics.sh
```

预期：输出 `短超时诊断门禁验证通过`；诊断目录包含 timeout marker、进程快照和非空 JVM dump；脚本
总退出码为 0，fixture 记录的两个 PID 都已不存在。

- [ ] **步骤 4：提交自验夹具**

```bash
git add scripts/verify-short-timeout-diagnostics.sh \
  verification/ci/HangingJvmFixture.java
git commit -m "test(ci): verify short-timeout diagnostic capture"
```

### 任务 3：把独立门禁接入 Linux CI

**文件：**

- 修改：`.github/workflows/ci.yml:59-74`

- [ ] **步骤 1：在全量构建前增加独立步骤**

在“确认构建与真实插件验收环境”之后增加：

```yaml
      - name: 验证短超时诊断门禁
        env:
          FIBRA_CI_DIAGNOSTICS_DIR: ${{ runner.temp }}/fibra-ci-hang-diagnostics
        run: scripts/verify-short-timeout-diagnostics.sh
```

保留后续两次 `run-ci-with-jvm-diagnostics.sh` 的现有环境和默认参数，不把 180 秒全量采样改成 10 秒。

- [ ] **步骤 2：验证 YAML、脚本和诊断目录上传路径**

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml", aliases: true)'
bash -n scripts/run-ci-with-jvm-diagnostics.sh \
  scripts/verify-short-timeout-diagnostics.sh
rg -n '验证短超时诊断门禁|fibra-ci-hang-diagnostics' .github/workflows/ci.yml
```

预期：YAML 可解析；新步骤和现有失败制品上传共同指向 `fibra-ci-hang-diagnostics`；脚本语法通过。

- [ ] **步骤 3：提交 CI 接线**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: enforce short-timeout diagnostic gate"
```

### 任务 4：完成本地回归与独立审查

**文件：**

- 审查：任务 1–3 的全部 diff
- 不新增产品代码或文档范围

- [ ] **步骤 1：运行首项定向门禁**

```bash
python3 verification/ci/test_verify_cli_tty.py
diagnostics_dir="$(mktemp -d)"
FIBRA_CI_DIAGNOSTICS_DIR="$diagnostics_dir" \
  scripts/verify-short-timeout-diagnostics.sh
git diff --check
```

预期：Python 2 项通过；短超时门禁通过；无空白错误。

- [ ] **步骤 2：运行完整 Maven 与仓外分发回归**

执行前按 `mvn-env` 规约使用本机 JDK 21 和 Maven 3.9.9：

```bash
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  /private/tmp/apache-maven-3.9.9/bin/mvn \
  --batch-mode --no-transfer-progress \
  -Dsurefire.timeout=300 -Dfailsafe.timeout=300 clean verify

diagnostics_dir="$(mktemp -d)"
env JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  MVN=/private/tmp/apache-maven-3.9.9/bin/mvn \
  FIBRA_CI_DIAGNOSTICS_DIR="$diagnostics_dir" \
  scripts/verify-distribution.sh
```

预期：全 reactor `BUILD SUCCESS`；仓外五类消费者、ZIP 和真实 TTY 验收通过。

- [ ] **步骤 3：进行独立审查**

使用 `superpowers:requesting-code-review`，重点确认：

- 没有产品模块、公共签名、POM 或版本改动；
- 正常命令退出码仍原样透传；
- 超时返回 124，且 `TERM` 后的进程树最终被清理；
- 诊断只保存进程快照、JVM dump 和有限输出尾部，不采集环境变量；
- CI 没有降低现有超时、关闭或发行门禁。

- [ ] **步骤 4：检查分支状态并等待用户推送后的 Linux CI**

```bash
git status --short --branch
git log -4 --oneline --decorate
```

预期：工作区干净，分支只新增本计划列出的三个实现提交。用户推送后，以同一 HEAD 的 Linux CI 全绿
作为首项最终完成证据；远端失败则保留诊断制品并按实际证据另立修复，不延长 10 秒门限。

## 计划自检

- 权威范围覆盖：故意挂起、进程/JVM 现场、有界退出、无遗留进程和正常路径均有对应任务。
- 兼容边界：无产品代码、公共 API、POM、依赖或版本变更。
- 复用核实：所有时间参数、返回码 124、诊断文件名和上传目录均来自当前实现。
- 完整性检查：所有步骤均给出确定文件、代码、命令和预期结果，没有未落定问题。
- 过度设计检查：未新增通用诊断协议、监控进程、第三方测试框架或生命周期修复。
