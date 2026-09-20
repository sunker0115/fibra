import importlib.util
import pathlib
import unittest


SCRIPT = pathlib.Path(__file__).parents[1] / "consumers" / "verify-cli-tty.py"
SPEC = importlib.util.spec_from_file_location("verify_cli_tty", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class DiagnosticTailTest(unittest.TestCase):
    def test_truncates_bytes_to_tail(self):
        self.assertEqual(
            MODULE.diagnostic_tail(b"abcdef", limit=4),
            "bytes=6 omitted=2 tail=b'cdef'",
        )

    def test_keeps_bytearray_repr_and_reports_no_omission(self):
        self.assertEqual(
            MODULE.diagnostic_tail(bytearray(b"abc"), limit=4),
            "bytes=3 omitted=0 tail=b'abc'",
        )

    def test_zero_limit_has_empty_tail(self):
        self.assertEqual(
            MODULE.diagnostic_tail(b"abc", limit=0),
            "bytes=3 omitted=3 tail=b''",
        )

    def test_default_limit_keeps_16_kib_tail(self):
        value = b"a" * (MODULE.DIAGNOSTIC_TAIL_BYTES + 1)
        expected_tail = b"a" * MODULE.DIAGNOSTIC_TAIL_BYTES
        self.assertEqual(
            MODULE.diagnostic_tail(value),
            f"bytes={len(value)} omitted=1 tail={expected_tail!r}",
        )


if __name__ == "__main__":
    unittest.main()
