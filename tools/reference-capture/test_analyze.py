"""Negative controls for reference acceptance. Test outputs remain available for inspection."""
import gzip
import json
from pathlib import Path
import tempfile
import unittest
import copy
from unittest.mock import patch

from analyze import CIRCUITS, check_receipt, save, sha, validate_trace


class ReferenceAcceptanceTest(unittest.TestCase):
    def fixture(self, change=None, change_decimal=None, change_decimal_text=None):
        root = Path(tempfile.mkdtemp(prefix='gecko-m4-reference-test-'))
        low = ['S.1@2:YOUT:0', 'U.1@1:YOUT:0']
        high = ['S.1@2:XIN:0', 'U.1@1:XIN:0']
        def voltage(terms, index):
            return {'name': 'V:' + '|'.join(terms) + ':ref=' + '|'.join(low), 'unit': 'V',
                    'kind': 'node_voltage', 'release_index': index, 'terminals': terms, 'reference_terminals': low}
        def device(name, unit, kind, component, index):
            return {'name': name + component, 'unit': unit, 'kind': kind, 'component': component,
                    'release_index': index, 'positive_terminal': 'XIN:0', 'negative_terminal': 'YOUT:0'}
        columns = [
            voltage(low, 0), voltage(high, 1),
            device('I:', 'A', 'component_current', 'U.1@1', 0),
            device('I:', 'A', 'component_current', 'S.1@2', 1),
            device('R:', 'ohm', 'device_resistance', 'S.1@2', 1),
            device('command:', '1', 'gate_command', 'S.1@2', 1),
            device('conducting:', '1', 'conduction_state', 'S.1@2', 1),
            device('I_aux:', 'A', 'auxiliary_current', 'U.1@1', 2),
        ]
        values = [[0., 0., 48., .1, -.1, .01, 0., 1., .1], [1e-6, 0., 47., .2, -.2, .01, 1., 1., .2]]
        if change:
            change(columns, values)
        header = ['time'] + [column['name'] for column in columns]
        decimal_values = copy.deepcopy(values)
        if change_decimal:
            change_decimal(decimal_values)
        for filename, formatter in [('trace.csv.gz', lambda x: format(x, '.12e')),
                                    ('trace-exact.csv.gz', float.hex)]:
            with gzip.open(root / filename, 'wt') as stream:
                stream.write(','.join(header) + '\n')
                for row in (decimal_values if filename == 'trace.csv.gz' else values):
                    text = ','.join(formatter(x) for x in row)
                    if filename == 'trace.csv.gz' and change_decimal_text:
                        text = change_decimal_text(text)
                    stream.write(text + '\n')
        save(root / 'schema.json', {'columns': columns})
        save(root / 'capture.json', {'steps': len(values), 'first_time': values[0][0], 'last_time': values[-1][0]})
        save(root / 'process.json', {'exit_code': 0, 'artifacts': {
            file.name: sha(file) for file in root.iterdir() if file.is_file()}})
        return root

    def validate(self, root):
        with patch.dict(CIRCUITS, {'fixture': 1e-6}):
            inventory = {'components': {'U.1@1', 'S.1@2'}, 'auxiliary': {'U.1@1'},
                         'devices': {'S.1@2'}, 'commands': {'S.1@2'}}
            return validate_trace(root, 'fixture', inventory)

    def test_accepts_command_and_conduction_as_different_quantities(self):
        result = self.validate(self.fixture())
        self.assertEqual(result['samples'], 2)
        self.assertEqual(result['binary_values_checked'], 4)

    def test_rejects_nonbinary_command(self):
        root = self.fixture(lambda columns, values: values[0].__setitem__(6, .5))
        with self.assertRaisesRegex(ValueError, 'binary'):
            self.validate(root)

    def test_rejects_current_mislabeled_as_voltage(self):
        root = self.fixture(lambda columns, values: columns[2].__setitem__('unit', 'V'))
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

    def test_rejects_missing_physical_bindings(self):
        root = self.fixture(lambda columns, values: [column.pop('release_index') for column in columns])
        with self.assertRaisesRegex(ValueError, 'binding'):
            self.validate(root)

    def test_rejects_missing_current_direction(self):
        root = self.fixture(lambda columns, values: columns[2].pop('positive_terminal'))
        with self.assertRaisesRegex(ValueError, 'direction'):
            self.validate(root)

    def test_rejects_decimal_error_as_tolerance_evidence(self):
        root = self.fixture(change_decimal=lambda values: values[0].__setitem__(1, 9.))
        with self.assertRaisesRegex(ValueError, 'rounding precision'):
            self.validate(root)

    def test_rejects_missing_gate_command_inventory(self):
        def remove_command(columns, values):
            columns.pop(5)
            for row in values:
                row.pop(6)
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.validate(self.fixture(remove_command))

    def test_rejects_missing_auxiliary_current_inventory(self):
        def remove_auxiliary(columns, values):
            columns.pop()
            for row in values:
                row.pop()
        with self.assertRaisesRegex(ValueError, 'inventory'):
            self.validate(self.fixture(remove_auxiliary))

    def test_rejects_non_normalized_zero_exponent(self):
        root = self.fixture(change_decimal_text=lambda text: text.replace('4.800000000000e+01', '0.000000000000e+99'))
        with self.assertRaisesRegex(ValueError, 'Non-normalized'):
            self.validate(root)


if __name__ == '__main__':
    unittest.main()
