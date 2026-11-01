"""실제 launch 경계의 취소·설정 실패가 소유한 child와 stream을 남기지 않는다."""
import errno
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("index_cost_launch", ROOT / "Scripts/verify-index-cost.py")
if SPEC is None or SPEC.loader is None:
    raise RuntimeError("module specification with a loader is required")
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


class IndexCostLaunchTest(unittest.TestCase):
    def setUp(self):
        if threading.current_thread() is not threading.main_thread():
            self.skipTest("real parent signal controls require the main thread")
        try:
            self.observe = probe.nonreaping_waiter()
            self.census = probe.group_census()
        except probe.DiagnosticError as error:
            self.skipTest(str(error))
        self.original_popen = subprocess.Popen
        self.original_owner = probe.OwnedProcessGroup
        self.original_owner_close = probe.OwnedProcessGroup.close_signals
        self.original_kill = probe.kill_group
        self.original_wait4 = os.wait4
        self.original_waitpid = os.waitpid
        self.original_thread = threading.Thread
        self.original_signal = signal.signal
        self.original_path_open = Path.open
        self.handlers = {number: signal.getsignal(number) for number in (signal.SIGINT, signal.SIGTERM)}
        self.children = []
        self.readers = []
        self.workers = []
        self.destinations = []
        self.externally_reaped = {}
        self.addCleanup(self._restore_handlers)
        self.addCleanup(self._cleanup_resources)

    def _restore_handlers(self):
        for number, handler in self.handlers.items():
            self.original_signal(number, handler)

    def _spawn(self, *arguments, **keywords):
        process = self.original_popen(*arguments, **keywords)
        self.children.append((process, (process.stdout, process.stderr)))
        return process

    def _cleanup_resources(self):
        # 실패한 구버전도 저장한 원래 owner와 WNOWAIT/census로만 정리한다.
        errors = []
        for process, sources in reversed(self.children):
            try:
                if process.returncode is None:
                    try:
                        self.observe(process.pid)
                    except ChildProcessError as error:
                        if error.errno != errno.ECHILD:
                            raise
                        process.returncode = self.externally_reaped.get(process.pid, 0)
                    else:
                        owned = self.original_owner(process, self.observe, self.census)
                        self.original_owner_close(owned)
                        process.wait(timeout=3)
                if process.returncode is None:
                    raise AssertionError("diagnostic child was not reaped")
            except BaseException as error:
                errors.append(error)
            finally:
                for source in sources:
                    try:
                        source.close()
                    except BaseException as error:
                        errors.append(error)
        for reader in self.readers:
            if reader.thread.ident is not None:
                reader.thread.join(timeout=3)
                if reader.thread.is_alive():
                    errors.append(AssertionError("diagnostic reader remained alive"))
        for worker in self.workers:
            worker.join(timeout=3)
            if worker.is_alive():
                errors.append(AssertionError("diagnostic caller remained alive"))
        for destination in self.destinations:
            if not destination.closed:
                destination.close()
        if errors:
            raise AssertionError("diagnostic cleanup failed", errors)

    def _run(self, root, code="import time; time.sleep(5)", **keywords):
        return probe.run([sys.executable, "-c", code], root, dict(os.environ), timeout=2, **keywords)

    def _assert_reaped(self, process):
        self.assertIsNotNone(process.returncode, "run left its actual child live or unreaped")
        with self.assertRaises(ChildProcessError) as caught:
            self.observe(process.pid)
        self.assertEqual(errno.ECHILD, caught.exception.errno)

    def _assert_sources_closed(self, process):
        sources = next(sources for child, sources in self.children if child is process)
        self.assertTrue(all(source.closed for source in sources), "run left a child pipe open")

    def _assert_cleanup_error(self, primary, stage, secondary):
        errors = getattr(primary, "launch_cleanup_errors", ())
        self.assertIsInstance(errors, list)
        self.assertTrue(any(name == stage and error is secondary for name, error in errors),
                        f"missing original secondary exception for {stage}: {errors!r}")

    def _spool_open(self, target, close_error=None):
        original_open = self.original_path_open
        destinations = self.destinations

        class Destination:
            def __init__(self, stream):
                self.stream = stream

            @property
            def closed(self):
                return self.stream.closed

            def write(self, data):
                return self.stream.write(data)

            def close(self):
                self.stream.close()
                if close_error is not None:
                    raise close_error

        def open_path(path, mode="r", *arguments, **keywords):
            stream = original_open(path, mode, *arguments, **keywords)
            if path == target and mode == "xb":
                destinations.append(stream)
                return Destination(stream)
            return stream
        return mock.patch.object(Path, "open", new=open_path)

    def _reader_factory(self, primary, *, construct_failure=None, start_failure=None,
                        join_error=None):
        created, started, joined = [], [], []
        original_thread = self.original_thread
        tracked = self.readers

        class Reader:
            def __init__(self, index, *arguments, **keywords):
                self.index = index
                self.thread = original_thread(*arguments, **keywords)
                tracked.append(self)

            def start(self):
                if self.index == start_failure:
                    raise primary
                self.thread.start()
                started.append(self.index)

            def join(self, timeout=None):
                joined.append(self.index)
                self.thread.join(timeout=timeout)
                if join_error is not None:
                    raise join_error

            def is_alive(self):
                return self.thread.is_alive()

        def construct(*arguments, **keywords):
            index = len(created)
            created.append(index)
            if index == construct_failure:
                raise primary
            return Reader(index, *arguments, **keywords)
        return construct, started, joined

    def test_real_default_sigint_between_popen_creation_and_return_is_reaped(self):
        self.original_signal(signal.SIGINT, signal.default_int_handler)
        exposed = []

        def interrupted_spawn(*arguments, **keywords):
            process = self._spawn(*arguments, **keywords)
            exposed.append(process)
            os.kill(os.getpid(), signal.SIGINT)
            return process

        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=interrupted_spawn):
            with self.assertRaises(KeyboardInterrupt):
                self._run(temporary)
        self.assertEqual(1, len(exposed))
        self._assert_reaped(exposed[0])
        self._assert_sources_closed(exposed[0])
        self.assertIs(signal.default_int_handler, signal.getsignal(signal.SIGINT))

    def test_real_sigint_during_native_reader_start_waits_for_binding_and_join(self):
        self.original_signal(signal.SIGINT, signal.default_int_handler)
        entered, release = threading.Event(), threading.Event()
        readers, joined, thread_errors, fixture_errors = [], [], [], []
        original_thread = self.original_thread
        original_bootstrap = getattr(original_thread, "_bootstrap_inner")
        original_excepthook = threading.excepthook

        class GatedReader(original_thread):
            def __init__(self, *arguments, **keywords):
                self.index = len(readers)
                self.native_entered = False
                super().__init__(*arguments, **keywords)
                readers.append(self)

            def _bootstrap_inner(self):
                self.native_entered = True
                if self.index == 0:
                    entered.set()
                    if not release.wait(timeout=3):
                        fixture_errors.append("native reader gate timed out")
                original_bootstrap(self)

            def join(self, timeout=None):
                joined.append(self.index)
                return super().join(timeout=timeout)

        def interrupt():
            try:
                if not entered.wait(timeout=3):
                    fixture_errors.append("sender did not observe the native reader")
                    return
                os.kill(os.getpid(), signal.SIGINT)
                probe.time.sleep(0.2)
            finally:
                release.set()

        def capture_thread_error(arguments):
            thread_errors.append(arguments.exc_value)
            original_excepthook(arguments)

        sender = original_thread(target=interrupt)
        self.workers.append(sender)

        def release_and_join():
            release.set()
            sender.join(timeout=3)
            try:
                self._cleanup_resources()
            finally:
                # ident 이전에 실행 중인 native thread도 fixture가 반드시 회수한다.
                for reader in readers:
                    if reader.native_entered:
                        if not getattr(reader, "_started").wait(timeout=3):
                            fixture_errors.append("native reader did not bind after release")
                        else:
                            original_thread.join(reader, timeout=3)
                            if reader.is_alive():
                                fixture_errors.append("native reader remained alive")

        self.addCleanup(release_and_join)
        sender.start()
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                probe.threading, "Thread", GatedReader), mock.patch.object(
                threading, "excepthook", new=capture_thread_error):
            try:
                with self.assertRaises(KeyboardInterrupt):
                    self._run(temporary)
                released_at_exit = release.is_set()
                joined_at_exit = list(joined)
                native_at_exit = [reader.index for reader in readers if reader.native_entered]
                self._assert_reaped(self.children[-1][0])
            finally:
                release_and_join()
        self.assertEqual([], fixture_errors)
        self.assertTrue(released_at_exit, "run exited before the native reader could bind")
        self.assertEqual(native_at_exit, joined_at_exit)
        self.assertEqual([], thread_errors)
        self._assert_sources_closed(self.children[-1][0])

    def test_owner_constructor_primary_and_unbound_cleanup_error_keep_identity(self):
        primary = RuntimeError("owner-construction-primary")
        secondary = OSError("signal-close-secondary")
        signaled = []

        def kill_then_fail(process):
            self.original_kill(process)
            signaled.append(process.pid)
            raise secondary

        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                probe, "OwnedProcessGroup", side_effect=primary), mock.patch.object(
                probe, "kill_group", side_effect=kill_then_fail):
            with self.assertRaises(RuntimeError) as caught:
                self._run(temporary)
        self.assertIs(primary, caught.exception)
        self._assert_cleanup_error(primary, "unbound-signal-close", secondary)
        self.assertEqual([self.children[0][0].pid], signaled)
        self._assert_reaped(self.children[0][0])
        self._assert_sources_closed(self.children[0][0])

    def test_real_flag_only_handlers_are_deferred_and_replayed_without_keyboard_interrupt(self):
        for owner_failure in (False, True):
            with self.subTest(owner_failure=owner_failure):
                primary = RuntimeError("owner-construction-primary")
                calls, inside_spawn = [], [False]

                def flag(number, frame):
                    calls.append((number, inside_spawn[0], frame is not None))

                self.original_signal(signal.SIGINT, flag)
                self.original_signal(signal.SIGTERM, flag)

                def signaled_spawn(*arguments, **keywords):
                    process = self._spawn(*arguments, **keywords)
                    inside_spawn[0] = True
                    try:
                        os.kill(os.getpid(), signal.SIGINT)
                        os.kill(os.getpid(), signal.SIGTERM)
                    finally:
                        inside_spawn[0] = False
                    return process

                def owner(*arguments):
                    if owner_failure:
                        raise primary
                    return self.original_owner(*arguments)

                with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                        probe.subprocess, "Popen", side_effect=signaled_spawn), mock.patch.object(
                        probe, "OwnedProcessGroup", side_effect=owner):
                    if owner_failure:
                        with self.assertRaises(RuntimeError) as caught:
                            self._run(temporary)
                        self.assertIs(primary, caught.exception)
                    else:
                        result = self._run(temporary, "print('flag-handler-ok')")
                        self.assertEqual(0, result["exit"])
                        self.assertEqual(b"flag-handler-ok\n", result["stdout"])
                self.assertEqual([(signal.SIGINT, False, True), (signal.SIGTERM, False, True)], calls)
                self.assertIs(flag, signal.getsignal(signal.SIGINT))
                self.assertIs(flag, signal.getsignal(signal.SIGTERM))
                self._assert_reaped(self.children[-1][0])

    def test_ignored_dispositions_are_never_mutated_and_are_inherited(self):
        for number in (signal.SIGINT, signal.SIGTERM):
            self.original_signal(number, signal.SIG_IGN)
        mutations = []

        def change_handler(number, handler):
            if number in (signal.SIGINT, signal.SIGTERM):
                mutations.append((number, handler))
            return self.original_signal(number, handler)

        def ignored_spawn(*arguments, **keywords):
            self.assertEqual(signal.SIG_IGN, signal.getsignal(signal.SIGINT))
            self.assertEqual(signal.SIG_IGN, signal.getsignal(signal.SIGTERM))
            process = self._spawn(*arguments, **keywords)
            os.kill(os.getpid(), signal.SIGINT)
            os.kill(os.getpid(), signal.SIGTERM)
            return process

        code = "import json, signal; print(json.dumps([signal.getsignal(signal.SIGINT), signal.getsignal(signal.SIGTERM)]))"
        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=ignored_spawn), mock.patch.object(
                probe.signal, "signal", side_effect=change_handler):
            result = self._run(temporary, code)
        self.assertEqual([int(signal.SIG_IGN)] * 2, json.loads(result["stdout"]))
        self.assertEqual([], mutations)
        self.assertEqual(signal.SIG_IGN, signal.getsignal(signal.SIGINT))
        self.assertEqual(signal.SIG_IGN, signal.getsignal(signal.SIGTERM))
        self._assert_reaped(self.children[-1][0])

    def test_owner_failure_refuses_signal_and_reap_after_returncode_or_echild(self):
        for set_returncode in (True, False):
            with self.subTest(set_returncode=set_returncode), tempfile.TemporaryDirectory() as temporary:
                unrelated = self._spawn([sys.executable, "-c", "import time; time.sleep(5)"],
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                        start_new_session=True)
                primary = RuntimeError("owner-construction-primary")
                target = []

                def reaped_spawn(*arguments, **keywords):
                    process = self._spawn(*arguments, **keywords)
                    target.append(process)
                    if set_returncode:
                        process.wait(timeout=2)
                    else:
                        pid, status = self.original_waitpid(process.pid, 0)
                        self.assertEqual(process.pid, pid)
                        self.externally_reaped[pid] = os.waitstatus_to_exitcode(status)
                    return process

                with mock.patch.object(probe.subprocess, "Popen", side_effect=reaped_spawn), mock.patch.object(
                        probe, "OwnedProcessGroup", side_effect=primary), mock.patch.object(
                        probe, "kill_group", wraps=self.original_kill) as kill, mock.patch.object(
                        probe.os, "wait4", wraps=self.original_wait4) as reap:
                    with self.assertRaises(RuntimeError) as caught:
                        self._run(temporary, "pass")
                self.assertIs(primary, caught.exception)
                kill.assert_not_called()
                reap.assert_not_called()
                self.assertFalse(self.observe(unrelated.pid), "run touched an unrelated live child")
                self.assertIsNone(unrelated.returncode)
                self._assert_sources_closed(target[0])

    def test_partial_reader_start_joins_only_started_reader_and_closes_pipes_and_spool(self):
        self._check_reader_failure(start_failure=1)

    def test_partial_reader_construction_joins_only_started_reader_and_closes_pipes_and_spool(self):
        self._check_reader_failure(construct_failure=1)

    def _check_reader_failure(self, **fault):
        primary = RuntimeError("reader-setup-primary")
        construct, started, joined = self._reader_factory(primary, **fault)
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "stdout"
            with self._spool_open(target), mock.patch.object(
                    probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                    probe.threading, "Thread", side_effect=construct):
                with self.assertRaises(RuntimeError) as caught:
                    self._run(temporary, stdout_path=target)
        self.assertIs(primary, caught.exception)
        self.assertEqual(started, joined)
        self.assertTrue(all(not reader.thread.is_alive() for reader in self.readers))
        self.assertTrue(self.destinations[0].closed)
        self._assert_reaped(self.children[-1][0])
        self._assert_sources_closed(self.children[-1][0])

    def test_reader_join_secondary_preserves_start_failure_and_closes_destination(self):
        primary = RuntimeError("reader-start-primary")
        secondary = RuntimeError("reader-join-secondary")
        construct, started, joined = self._reader_factory(primary, start_failure=1, join_error=secondary)
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "stdout"
            with self._spool_open(target), mock.patch.object(
                    probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                    probe.threading, "Thread", side_effect=construct):
                with self.assertRaises(RuntimeError) as caught:
                    self._run(temporary, stdout_path=target)
        self.assertIs(primary, caught.exception)
        self._assert_cleanup_error(primary, "reader-join", secondary)
        self.assertEqual(started, joined)
        self.assertTrue(self.destinations[0].closed)
        self._assert_reaped(self.children[-1][0])

    def test_unstarted_source_close_secondary_preserves_constructor_failure(self):
        primary = RuntimeError("reader-construction-primary")
        secondary = OSError("source-close-secondary")
        construct, started, joined = self._reader_factory(primary, construct_failure=0)

        def wrapped_spawn(*arguments, **keywords):
            process = self._spawn(*arguments, **keywords)
            source = process.stdout
            if source is None:
                raise AssertionError("spawn did not create its stdout pipe")
            original_close = source.close

            def close_then_fail():
                original_close()
                raise secondary

            close_failure = mock.patch.object(source, "close", side_effect=close_then_fail)
            close_failure.start()
            self.addCleanup(close_failure.stop)
            return process

        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=wrapped_spawn), mock.patch.object(
                probe.threading, "Thread", side_effect=construct):
            with self.assertRaises(RuntimeError) as caught:
                self._run(temporary)
        self.assertIs(primary, caught.exception)
        self._assert_cleanup_error(primary, "unstarted-source-close", secondary)
        self.assertEqual([], started)
        self.assertEqual([], joined)
        self._assert_reaped(self.children[-1][0])
        self._assert_sources_closed(self.children[-1][0])

    def test_bound_signal_close_secondary_preserves_reader_constructor_failure(self):
        primary = RuntimeError("reader-construction-primary")
        secondary = OSError("signal-close-secondary")
        construct, _, _ = self._reader_factory(primary, construct_failure=0)

        def close_then_fail(owned):
            self.original_owner_close(owned)
            raise secondary

        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                probe.threading, "Thread", side_effect=construct), mock.patch.object(
                self.original_owner, "close_signals", new=close_then_fail):
            with self.assertRaises(RuntimeError) as caught:
                self._run(temporary)
        self.assertIs(primary, caught.exception)
        self._assert_cleanup_error(primary, "signal-close", secondary)
        self._assert_reaped(self.children[-1][0])

    def test_destination_close_secondary_preserves_owner_constructor_failure(self):
        primary = RuntimeError("owner-construction-primary")
        secondary = OSError("destination-close-secondary")
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "stdout"
            with self._spool_open(target, secondary), mock.patch.object(
                    probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                    probe, "OwnedProcessGroup", side_effect=primary):
                with self.assertRaises(RuntimeError) as caught:
                    self._run(temporary, stdout_path=target)
        self.assertIs(primary, caught.exception)
        self._assert_cleanup_error(primary, "destination-close", secondary)
        self.assertTrue(self.destinations[0].closed)
        self._assert_reaped(self.children[-1][0])

    def test_handler_restore_secondary_preserves_owner_constructor_failure(self):
        primary = RuntimeError("owner-construction-primary")
        secondary = RuntimeError("handler-restore-secondary")
        self.original_signal(signal.SIGINT, signal.default_int_handler)
        installed = []

        def signal_then_fail(number, handler):
            previous = self.original_signal(number, handler)
            if number == signal.SIGINT:
                if handler is signal.default_int_handler and installed:
                    raise secondary
                installed.append(handler)
            return previous

        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                probe, "OwnedProcessGroup", side_effect=primary), mock.patch.object(
                probe.signal, "signal", side_effect=signal_then_fail):
            with self.assertRaises(RuntimeError) as caught:
                self._run(temporary)
        self.assertIs(primary, caught.exception)
        self._assert_cleanup_error(primary, "signal-handler-restore", secondary)
        self.assertIs(signal.default_int_handler, signal.getsignal(signal.SIGINT))
        self._assert_reaped(self.children[-1][0])

    def test_spool_open_failure_happens_before_spawn(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "existing"
            target.write_bytes(b"preserved")
            with mock.patch.object(probe.subprocess, "Popen") as spawn:
                with self.assertRaises(FileExistsError):
                    self._run(temporary, stdout_path=target)
                spawn.assert_not_called()
            self.assertEqual(b"preserved", target.read_bytes())

    def test_signal_api_failure_happens_before_spawn_and_closes_any_open_spool(self):
        primary = ValueError("signal-handler-api-unavailable")
        self.original_signal(signal.SIGINT, signal.default_int_handler)
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / "stdout"
            with self._spool_open(target), mock.patch.object(
                    probe.subprocess, "Popen", side_effect=AssertionError("signal API failure did not prevent spawn")) as spawn, mock.patch.object(
                    probe.signal, "signal", side_effect=primary):
                with self.assertRaises(ValueError) as caught:
                    self._run(temporary, stdout_path=target)
                self.assertIs(primary, caught.exception)
                spawn.assert_not_called()
        self.assertTrue(all(destination.closed for destination in self.destinations))
        self.assertIs(signal.default_int_handler, signal.getsignal(signal.SIGINT))

    def test_partial_signal_install_failure_restores_prior_handler_before_spawn(self):
        primary = ValueError("second-signal-handler-api-unavailable")
        flags = []

        def flag(number, frame):
            flags.append(number)

        self.original_signal(signal.SIGINT, signal.default_int_handler)
        self.original_signal(signal.SIGTERM, flag)

        def fail_second(number, handler):
            if number == signal.SIGTERM and handler is not flag:
                raise primary
            return self.original_signal(number, handler)

        with tempfile.TemporaryDirectory() as temporary, mock.patch.object(
                probe.subprocess, "Popen", side_effect=AssertionError("partial signal API failure did not prevent spawn")) as spawn, mock.patch.object(
                probe.signal, "signal", side_effect=fail_second):
            with self.assertRaises(ValueError) as caught:
                self._run(temporary)
            self.assertIs(primary, caught.exception)
            spawn.assert_not_called()
        self.assertIs(signal.default_int_handler, signal.getsignal(signal.SIGINT))
        self.assertIs(flag, signal.getsignal(signal.SIGTERM))
        self.assertEqual([], flags)

    def test_worker_caller_skips_signal_mutation_and_still_reaps_its_child(self):
        results, errors = [], []
        construct, started, joined = self._reader_factory(RuntimeError("unused"))
        with tempfile.TemporaryDirectory() as temporary:
            def invoke():
                try:
                    results.append(self._run(temporary, "print('worker-ok')"))
                except BaseException as error:
                    errors.append(error)

            worker = self.original_thread(target=invoke)
            self.workers.append(worker)
            with mock.patch.object(probe.subprocess, "Popen", side_effect=self._spawn), mock.patch.object(
                    probe.threading, "Thread", side_effect=construct), mock.patch.object(
                    probe.signal, "signal", side_effect=AssertionError("worker mutated process signal handlers")) as change_handler:
                worker.start()
                worker.join(timeout=3)
                self.assertFalse(worker.is_alive(), "worker run did not finish")
                change_handler.assert_not_called()
            self.assertEqual([], errors)
            self.assertEqual(1, len(results))
            self.assertEqual(0, results[0]["exit"])
            self.assertEqual(b"worker-ok\n", results[0]["stdout"])
        self.assertEqual(started, joined)
        self._assert_reaped(self.children[-1][0])
        self._assert_sources_closed(self.children[-1][0])


if __name__ == "__main__":
    unittest.main()
