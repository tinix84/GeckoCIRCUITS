"""Negative controls for reference acceptance. Test outputs remain available for inspection."""
import gzip
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from analyze import CIRCUITS, check_receipt, save, sha, validate_trace


class ReferenceAcceptanceTest(unittest.TestCase):
    def fixture(self, change=None):
        root = Path(tempfile.mkdtemp(prefix='gecko-m4-reference-test-'))
        columns = [
            {'name': 'V:U.1:XIN:0:ref=U.1:YOUT:0', 'unit': 'V', 'kind': 'node_voltage'},
            {'name': 'I:U.1', 'unit': 'A', 'kind': 'component_current'},
            {'name': 'command:S.1', 'unit': '1', 'kind': 'gate_command'},
            {'name': 'conducting:S.1', 'unit': '1', 'kind': 'conduction_state'},
        ]
        values = [[0., 48., .1, 0., 1.], [1e-6, 47., .2, 1., 1.]]
        if change:
            change(columns, values)
        header = ['time'] + [column['name'] for column in columns]
        for filename, formatter in [('trace.csv.gz', lambda x: format(x, '.12e')),
                                    ('trace-exact.csv.gz', float.hex)]:
            with gzip.open(root / filename, 'wt') as stream:
                stream.write(','.join(header) + '\n')
                for row in values:
                    stream.write(','.join(formatter(x) for x in row) + '\n')
        save(root / 'schema.json', {'columns': columns})
        save(root / 'capture.json', {'steps': len(values), 'first_time': values[0][0], 'last_time': values[-1][0]})
        save(root / 'process.json', {'exit_code': 0, 'artifacts': {
            file.name: sha(file) for file in root.iterdir() if file.is_file()}})
        return root

    def validate(self, root):
        with patch.dict(CIRCUITS, {'fixture': 1e-6}):
            return validate_trace(root, 'fixture')

    def test_accepts_command_and_conduction_as_different_quantities(self):
        result = self.validate(self.fixture())
        self.assertEqual(result['samples'], 2)
        self.assertEqual(result['binary_values_checked'], 4)

    def test_rejects_nonbinary_command(self):
        root = self.fixture(lambda columns, values: values[0].__setitem__(3, .5))
        with self.assertRaisesRegex(ValueError, 'binary'):
            self.validate(root)

    def test_rejects_current_mislabeled_as_voltage(self):
        root = self.fixture(lambda columns, values: columns[1].__setitem__('unit', 'V'))
        with self.assertRaisesRegex(ValueError, 'physical unit'):
            self.validate(root)

    def test_rejects_duplicate_identity(self):
        root = self.fixture(lambda columns, values: columns[1].__setitem__('name', columns[0]['name']))
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.validate(root)

    def test_rejects_shifted_step(self):
        root = self.fixture(lambda columns, values: values[1].__setitem__(0, 2e-6))
        with self.assertRaisesRegex(ValueError, 'spacing'):
            self.validate(root)

    def test_rejects_incomplete_horizon(self):
        root = self.fixture(lambda columns, values: values.pop())
        with self.assertRaisesRegex(ValueError, 'endpoint'):
            self.validate(root)

    def test_rejects_truncated_record(self):
        root = self.fixture(lambda columns, values: values[1].pop())
        with self.assertRaisesRegex(ValueError, 'truncated'):
            self.validate(root)

    def test_rejects_nonfinite_signal(self):
        root = self.fixture(lambda columns, values: values[1].__setitem__(1, float('nan')))
        with self.assertRaisesRegex(ValueError, 'notation'):
            self.validate(root)

    def test_rejects_modified_artifact(self):
        root = self.fixture()
        with (root / 'trace.csv.gz').open('ab') as stream:
            stream.write(b'corruption')
        with self.assertRaisesRegex(ValueError, 'integrity'):
            check_receipt(root)


if __name__ == '__main__':
    unittest.main()
