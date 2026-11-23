#!/usr/bin/env python3
"""Validate release-only capture evidence and propose absolute tolerances for review."""
import argparse
import csv
from decimal import Decimal
import gzip
import hashlib
from itertools import zip_longest
import json
import math
from pathlib import Path
import re

from capture import CIRCUITS, RELEASE, ROOT, release_input_sha, save, sha


def decoded_hash(path):
    digest = hashlib.sha256()
    with gzip.open(path, 'rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def check_receipt(directory):
    receipt = json.loads((directory / 'process.json').read_text())
    if receipt['exit_code'] != 0:
        raise ValueError('Unsuccessful process receipt.')
    for file, expected in receipt['artifacts'].items():
        if sha(directory / file) != expected:
            raise ValueError(f'Artifact integrity mismatch: {directory / file}')
    return receipt


def validate_trace(directory, circuit):
    check_receipt(directory)
    schema = json.loads((directory / 'schema.json').read_text())
    columns = schema['columns']
    units = {'node_voltage': 'V', 'auxiliary_current': 'A', 'component_current': 'A',
             'device_resistance': 'ohm', 'gate_command': '1', 'conduction_state': '1'}
    for column in columns:
        if column['kind'] not in units or column['unit'] != units[column['kind']]:
            raise ValueError('Unknown signal kind or incorrect physical unit.')
    expected_header = ['time'] + [column['name'] for column in columns]
    if len(set(expected_header)) != len(expected_header):
        raise ValueError('Duplicate signal identities.')
    summary = json.loads((directory / 'capture.json').read_text())
    measurements = {unit: {'max_abs': 0., 'max_ulp': 0., 'max_serialization_error': 0.,
                           'max_error_signal': None, 'max_error_step': None}
                    for unit in ('V', 'A', 'ohm')}
    previous = None
    count = 0
    max_timestamp_roundtrip_error = 0.
    discrete_count = 0
    with gzip.open(directory / 'trace.csv.gz', 'rt') as decimal_stream, \
            gzip.open(directory / 'trace-exact.csv.gz', 'rt') as exact_stream:
        decimal_rows, exact_rows = csv.reader(decimal_stream), csv.reader(exact_stream)
        if next(decimal_rows) != expected_header or next(exact_rows) != expected_header:
            raise ValueError('Header/schema mismatch.')
        for count, pair in enumerate(zip_longest(decimal_rows, exact_rows), 1):
            decimal_row, exact_row = pair
            if decimal_row is None or exact_row is None or len(decimal_row) != len(expected_header) or len(exact_row) != len(expected_header):
                raise ValueError('Missing or truncated trace row.')
            if not all(re.fullmatch(r'-?\d\.\d{12}e[+-]\d+', cell) for cell in decimal_row):
                raise ValueError('Invalid scientific notation or precision.')
            actual_time = float.fromhex(exact_row[0])
            if not math.isfinite(actual_time) or (previous is not None and actual_time <= previous):
                raise ValueError('Nonfinite or unordered native timestamps.')
            if count == 1 and actual_time != 0.:
                raise ValueError('Missing startup step.')
            if abs(actual_time - (count - 1) * 1e-6) > 1e-12:
                raise ValueError('Step spacing differs from saved dt.')
            max_timestamp_roundtrip_error = max(max_timestamp_roundtrip_error, abs(float(decimal_row[0]) - actual_time))
            if max_timestamp_roundtrip_error > 1e-12:
                raise ValueError('Timestamp formatting exceeds 1 ps.')
            for column, rounded_cell, exact_cell in zip(columns, decimal_row[1:], exact_row[1:]):
                value, rounded = float.fromhex(exact_cell), float(rounded_cell)
                if not math.isfinite(value) or not math.isfinite(rounded):
                    raise ValueError('Nonfinite signal.')
                if column['kind'] in ('gate_command', 'conduction_state'):
                    if value not in (0., 1.) or rounded != value:
                        raise ValueError('Invalid binary signal.')
                    discrete_count += 1
                else:
                    measure = measurements[column['unit']]
                    measure['max_abs'] = max(measure['max_abs'], abs(value))
                    measure['max_ulp'] = max(measure['max_ulp'], math.ulp(value))
                    # Decimal comparison measures text rounding separately from binary parsing.
                    error = float(abs(Decimal(rounded_cell) - Decimal.from_float(value)))
                    if error > measure['max_serialization_error']:
                        measure.update(max_serialization_error=error,
                                       max_error_signal=column['name'], max_error_step=count - 1)
            previous = actual_time
    if count == 0 or count != summary['steps'] or previous != summary['last_time']:
        raise ValueError('Capture summary/sample count mismatch.')
    if previous > CIRCUITS[circuit] or previous + 1e-6 <= CIRCUITS[circuit]:
        raise ValueError('Capture does not reach the normal release-run endpoint.')
    return {'samples': count, 'signals': len(columns), 'first_time': summary['first_time'],
            'last_time': previous, 'max_timestamp_serialization_error_s': max_timestamp_roundtrip_error,
            'binary_values_checked': discrete_count, 'unit_measurements': measurements,
            'trace_sha256': sha(directory / 'trace.csv.gz'),
            'schema_sha256': sha(directory / 'schema.json')}


def validate_scope(path, expected_samples):
    with gzip.open(path, 'rt') as stream:
        reader = csv.reader(stream)
        header = next(reader)
        if len(header) < 2 or header[0] != 'time' or len(header) != len(set(header)):
            raise ValueError('Invalid SCOPE witness header.')
        count = 0
        for count, row in enumerate(reader, 1):
            if len(row) != len(header):
                raise ValueError('Truncated SCOPE witness.')
            timestamp = float(row[0])
            if not math.isfinite(timestamp) or abs(timestamp - (count - 1) * 1e-6) > 1e-12:
                raise ValueError('Invalid reconstructed SCOPE clock.')
            if not all(math.isfinite(float.fromhex(value)) for value in row[1:]):
                raise ValueError('Nonfinite SCOPE witness signal.')
        if count != expected_samples:
            raise ValueError('SCOPE witness omits committed steps.')
    return count


def analyze(args):
    selected = args.circuits or list(CIRCUITS)
    results = {}
    for circuit in selected:
        base = args.evidence / args.run_group / circuit
        repeats = [base / f'observer-{i}' for i in range(1, 4)]
        stock = base / 'stock-1'
        stock_receipt = check_receipt(stock)
        reports = [validate_trace(folder, circuit) for folder in repeats]
        for folder in [stock, *repeats]:
            validate_scope(folder / 'scope.csv.gz', reports[0]['samples'])
        for artifact in ('schema.json', 'capture.json'):
            if len({sha(folder / artifact) for folder in repeats}) != 1:
                raise ValueError(f'Unexplained schema/native-summary variation: {circuit}')
        for artifact in ('trace.csv.gz', 'trace-exact.csv.gz'):
            if len({decoded_hash(folder / artifact) for folder in repeats}) != 1:
                raise ValueError(f'Unexplained reference variation: {circuit} {artifact}')
        witnesses = {decoded_hash(folder / 'scope.csv.gz') for folder in [stock, *repeats]}
        if len(witnesses) != 1:
            raise ValueError(f'Stock/instrumented SCOPE mismatch: {circuit}')
        if len({check_receipt(folder)['build_receipt_sha256'] for folder in [stock, *repeats]}) != 1:
            raise ValueError('Witness and reference builds differ.')
        input_path = ROOT / 'src/test/resources/ipes/education' / (circuit + '.ipes')
        if sha(input_path) != stock_receipt['input_sha256']:
            raise ValueError('Circuit changed since capture.')
        if sha(input_path) != release_input_sha(circuit):
            raise ValueError('Captured circuit differs from the pinned release.')
        results[circuit] = dict(reports[0], repeats=3, repeat_exact_values_identical=True,
                               stock_scope_witness_identical=True, input_sha256=sha(input_path),
                               max_observed_repeat_variation=0., reference_path=str(repeats[0]))
        print(f'VALIDATED {circuit}: {reports[0]["samples"]} steps; three exact repeats; stock witness identical', flush=True)
    proposed = {}
    for unit in ('V', 'A', 'ohm'):
        maxima = {key: max(result['unit_measurements'][unit][key] for result in results.values())
                  for key in ('max_serialization_error', 'max_ulp', 'max_abs')}
        floor = maxima['max_serialization_error'] + 2 * maxima['max_ulp']
        # Round upward to one significant decimal digit. This is a proposal, not approval.
        magnitude = 10 ** math.floor(math.log10(floor)) if floor else 0.
        proposal = math.ceil(floor / magnitude) * magnitude if floor else 0.
        proposed[unit] = dict(maxima, proposed_absolute_tolerance=proposal,
                              basis='maximum measured serialization error + two maximum binary ULPs; rounded upward',
                              approved=False)
    report = {'release_commit': RELEASE, 'core_comparison_executed': False,
              'checkpoint': 'AWAITING_USER_ABSOLUTE_TOLERANCE_REVIEW',
              'all_ten_validated': set(selected) == set(CIRCUITS), 'circuits': results,
              'proposed_tolerances': proposed,
              'limitations': ['Single machine/JVM environment.',
                             'Exact repeats show observed determinism, not a bound for every environment.',
                             'Absolute proposals reflect capture resolution, not independent physical uncertainty.',
                             'Stock witnesses cover configured float-valued SCOPE signals and a reconstructed clock.']}
    if args.output:
        save(args.output, report)
    print(json.dumps({'all_ten_validated': report['all_ten_validated'],
                      'proposed_tolerances': proposed, 'core_comparison_executed': False}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, default=ROOT.parent / 'm4-reference-evidence-20261123')
    parser.add_argument('--run-group', default='runs')
    parser.add_argument('--circuits', nargs='+', choices=list(CIRCUITS))
    parser.add_argument('--output', type=Path)
    analyze(parser.parse_args())
