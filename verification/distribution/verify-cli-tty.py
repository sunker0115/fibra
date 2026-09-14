#!/usr/bin/env python3
import fcntl
import os
import pty
import re
import select
import signal
import struct
import subprocess
import sys
import termios
import time


ANSI = re.compile(rb"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1b\\))")


class Session:
    def __init__(self, command):
        self.master, slave = pty.openpty()
        self.closed = False
        self.resize(80, 24)
        environment = os.environ.copy()
        environment["TERM"] = "xterm-256color"
        environment.pop("NO_COLOR", None)
        environment.pop("JAVA_TOOL_OPTIONS", None)
        self.process = subprocess.Popen(
            command,
            stdin=slave, stdout=subprocess.PIPE, stderr=slave, env=environment, close_fds=True)
        os.close(slave)
        self.stdout = bytearray()
        self.terminal = bytearray()

    def resize(self, columns, rows):
        fcntl.ioctl(self.master, termios.TIOCSWINSZ,
                    struct.pack("HHHH", rows, columns, 0, 0))
        if hasattr(self, "process"):
            os.kill(self.process.pid, signal.SIGWINCH)

    def send(self, value):
        os.write(self.master, value)

    def wait_for(self, stream, expected, count=1, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            value = self.plain_terminal() if stream == "terminal" else bytes(self.stdout)
            if value.count(expected) >= count:
                return
            self.pump(min(0.1, deadline - time.monotonic()))
        raise AssertionError(
            f"等待 {stream} 中的 {expected!r} 超时\nstdout={bytes(self.stdout)!r}\n"
            f"terminal={bytes(self.terminal)!r}")

    def pump(self, timeout):
        descriptors = [self.master]
        if self.process.stdout is not None:
            descriptors.append(self.process.stdout.fileno())
        ready, _, _ = select.select(descriptors, [], [], max(0, timeout))
        for descriptor in ready:
            try:
                data = os.read(descriptor, 4096)
            except OSError:
                data = b""
            if descriptor == self.master:
                self.terminal.extend(data)
            else:
                self.stdout.extend(data)

    def plain_terminal(self):
        return ANSI.sub(b"", bytes(self.terminal)).replace(b"\r", b"")

    def finish(self):
        deadline = time.monotonic() + 10
        while self.process.poll() is None and time.monotonic() < deadline:
            self.pump(0.1)
        if self.process.poll() is None:
            terminal_attributes = self.terminal_attributes()
            thread_dump = self.capture_thread_dump()
            raise AssertionError(
                f"交互 fixture 未在 10 秒内退出\n"
                f"SIGQUIT={thread_dump}\nterminal_attributes={terminal_attributes}\n"
                f"stdout={bytes(self.stdout)!r}\nterminal={bytes(self.terminal)!r}")
        for _ in range(5):
            self.pump(0)
        if self.process.returncode != 0:
            raise AssertionError(
                f"交互 fixture 退出码 {self.process.returncode}\n"
                f"stdout={bytes(self.stdout)!r}\nterminal={bytes(self.terminal)!r}")
        self.close_master()

    def abort(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=2)
        self.close_master()

    def capture_thread_dump(self):
        try:
            os.kill(self.process.pid, signal.SIGQUIT)
        except OSError as error:
            return f"发送失败: {error}"
        deadline = time.monotonic() + 2
        while self.process.poll() is None and time.monotonic() < deadline:
            self.pump(min(0.1, deadline - time.monotonic()))
        return "已发送"

    def terminal_attributes(self):
        try:
            attributes = termios.tcgetattr(self.master)
            return {
                "iflag": attributes[0],
                "oflag": attributes[1],
                "cflag": attributes[2],
                "lflag": attributes[3],
                "veof": attributes[6][termios.VEOF],
                "vmin": attributes[6][termios.VMIN],
                "vtime": attributes[6][termios.VTIME],
            }
        except termios.error as error:
            return f"读取失败: {error}"

    def close_master(self):
        if not self.closed:
            os.close(self.master)
            self.closed = True


def first_session(java, classpath, home):
    session = Session(
        [java, "-cp", classpath, "verification.distribution.InteractiveCliFixture", home])
    try:
        session.wait_for("terminal", b"consumer> ")
        session.send(b"ec")
        session.wait_for("terminal", b"background-ready")
        session.send(b"ho preserved\r")
        session.wait_for("stdout", b"preserved\n")

        session.send(b"ec\tcompleted\r")
        session.wait_for("stdout", b"completed\n")

        session.send(b"screen\r")
        session.wait_for("stdout", b"screen-ready\n")
        session.resize(32, 10)
        time.sleep(0.15)
        session.send(b"\x1b[200~a\r\n/b\x03\x1b[201~\x1b[A")
        time.sleep(0.15)
        session.send(b"\x1b")
        time.sleep(0.15)
        session.send(b"\x1b[Z\x1b[13;5u")
        session.wait_for("stdout", b"screen-inputs=")

        summary = bytes(session.stdout)
        if b"PASTE:a\n/b\x03,UP,ESCAPE,TAB+SHIFT,ENTER+CONTROL" not in summary:
            raise AssertionError(f"renderer 未收到完整解码输入：{summary!r}")
        resize_match = re.search(rb"resizes=(\d+)", summary)
        if (b"size=32x10" not in summary or resize_match is None
                or int(resize_match.group(1)) < 2):
            raise AssertionError(f"renderer 未观察初始尺寸和 WINCH 后尺寸：{summary!r}")

        session.send(b"fail-screen\r")
        session.wait_for("terminal", b"expected-render-failure")
        session.send(b"echo after-failure\r")
        session.wait_for("stdout", b"after-failure\n")

        session.send(b"screen\r")
        session.wait_for("stdout", b"screen-ready\n", count=2)
        session.send(b"\x03")
        session.wait_for("stdout", b"screen-cancelled\n")
        session.send(b"echo after-cancel\r")
        session.wait_for("stdout", b"after-cancel\n")

        prompt_count = session.plain_terminal().count(b"consumer> ")
        session.send(b"echo history-restart\r")
        session.wait_for("stdout", b"history-restart\n")
        session.wait_for("terminal", b"consumer> ", count=prompt_count + 1)
        session.send(b"\x04")
        session.finish()

        if re.search(rb"\x1b\[(?:1;36|36;1)m", session.terminal) is None:
            raise AssertionError(
                f"真实 TTY 输出未包含已知命令的 cyan/bold 高亮：{bytes(session.terminal)!r}")
        terminal = session.plain_terminal()
        for expected in (b"background-ready", b"inputs=", b"size=32x10", b"consumer> "):
            if expected not in terminal:
                raise AssertionError(f"真实 TTY 呈现缺少 {expected!r}")
        if b"\x1b[?2004h" not in session.terminal or b"\x1b[?2004l" not in session.terminal:
            raise AssertionError("真实 TTY 未配对启用和恢复 bracketed paste")
    except BaseException:
        session.abort()
        raise


def restarted_session(java, classpath, home):
    session = Session(
        [java, "-cp", classpath, "verification.distribution.InteractiveCliFixture", home])
    try:
        session.wait_for("terminal", b"consumer> ")
        prompt_count = session.plain_terminal().count(b"consumer> ")
        session.send(b"\x1bOA\r")
        session.wait_for("stdout", b"history-restart\n")
        session.wait_for("terminal", b"consumer> ", count=prompt_count + 1)
        session.send(b"\x04")
        session.finish()
    except BaseException:
        session.abort()
        raise


def input_session(java, classpath, home):
    input_home = os.path.join(home, "input-mode")
    session = Session(
        [java, "-cp", classpath, "verification.distribution.InteractiveCliFixture",
         input_home, "input"])
    try:
        session.wait_for("terminal", b"consumer> ")
        session.send(b'  analyze "project  \r')
        session.wait_for("stdout", b'input:  analyze "project  \n')
        session.send(b"/exit\r")
        session.wait_for("stdout", b"input:/exit\n")
        session.finish()
        if os.path.exists(os.path.join(input_home, "repl.history")):
            raise AssertionError("应用原样输入被写入通用命令历史")
    except BaseException:
        session.abort()
        raise


def dynamic_plugin_session(launcher, home):
    session = Session([launcher, "--home", home, "repl"])
    try:
        session.wait_for("terminal", b"fibra> ")
        session.send(b"external-cli read-key\r")
        session.wait_for("stdout", b"ready\n")
        session.send(b"\x03")
        session.wait_for("stdout", b"cancelled\n")
        session.send(b"external-cli echo after-cancel\r")
        session.wait_for("stdout", b"after-cancel\n")
        session.send(b"exit\r")
        session.finish()
    except BaseException:
        session.abort()
        raise


def main():
    if len(sys.argv) == 5 and sys.argv[1] == "session":
        first_session(sys.argv[2], sys.argv[3], sys.argv[4])
        restarted_session(sys.argv[2], sys.argv[3], sys.argv[4])
        input_session(sys.argv[2], sys.argv[3], sys.argv[4])
        message = "仓库外 CliSession 真实 TTY 验证通过"
    elif len(sys.argv) == 4 and sys.argv[1] == "dynamic":
        dynamic_plugin_session(sys.argv[2], sys.argv[3])
        message = "仓库外动态插件真实 TTY 验证通过"
    else:
        raise SystemExit(
            "usage: verify-cli-tty.py session JAVA CLASSPATH HOME | "
            "dynamic LAUNCHER HOME")
    print(message)


if __name__ == "__main__":
    main()
