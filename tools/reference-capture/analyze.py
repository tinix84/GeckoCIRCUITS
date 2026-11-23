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
import os
from pathlib import Path
import re

from capture import CIRCUITS, RELEASE, ROOT, check_receipt, release_input_bytes, release_input_sha, save, sha


def decoded_hash(path):
    digest = hashlib.sha256()
    with gzip.open(path, 'rb') as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def pinned_inventory(circuit):
    text = gzip.decompress(release_input_bytes(circuit)).decode('latin1')
    inventory = {key: set() for key in ('components', 'auxiliary', 'devices', 'commands')}
    for block in re.findall(r'<ElementLK>\s*(.*?)<\\ElementLK>', text, re.S):
        fields = {key: re.findall(r'^' + key + r'\s+([^\n]+)', block, re.M)
                  for key in ('typ', 'uniqueObjectIdentifier', 'idStringDialog', 'enabledShorted')}
        if any(len(value) != 1 for value in fields.values()) or fields['enabledShorted'] != ['1']:
            raise ValueError('Ambiguous or unsupported release component inventory.')
        kind = int(fields['typ'][0])
        if kind not in (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12):
            raise ValueError('Release inventory needs explicit support for this device type.')
        if kind == 9:  # Mutual coupling is represented by its winding currents.
            continue
        identity = fields['idStringDialog'][0].strip() + '@' + fields['uniqueObjectIdentifier'][0].strip()
        if identity in inventory['components']:
            raise ValueError('Duplicate saved component identity.')
        inventory['components'].add(identity)
        if kind in (4, 12):
            inventory['auxiliary'].add(identity)
        if kind in (6, 7, 8, 10):
            inventory['devices'].add(identity)
        if kind in (7, 8, 10):
            inventory['commands'].add(identity)
    if not inventory['components']:
        raise ValueError('Missing pinned electrical inventory.')
    return inventory


def validate_schema(columns, inventory):
    units = {'node_voltage': 'V', 'auxiliary_current': 'A', 'component_current': 'A',
             'device_resistance': 'ohm', 'gate_command': '1', 'conduction_state': '1'}
    component_pattern = r'[A-Za-z0-9_.-]+@-?\d+'
    terminal_pattern = component_pattern + r':(?:XIN|YOUT):0'
    currents, nodes, auxiliary = {}, {}, set()
    device_groups = {kind: set() for kind in ('device_resistance', 'gate_command', 'conduction_state')}
    prefixes = {'component_current': 'I:', 'auxiliary_current': 'I_aux:',
                'device_resistance': 'R:', 'gate_command': 'command:', 'conduction_state': 'conducting:'}
    if not columns or len({column['name'] for column in columns}) != len(columns):
        raise ValueError('Duplicate or missing signal identities.')
    for column in columns:
        kind = column['kind']
        if kind not in units or column['unit'] != units[kind]:
            raise ValueError('Unknown signal kind or incorrect physical unit.')
        index = column.get('release_index')
        if type(index) is not int or index < 0:
            raise ValueError('Missing or invalid release-index binding.')
        if kind == 'node_voltage':
            for field in ('terminals', 'reference_terminals'):
                terms = column.get(field)
                if not isinstance(terms, list) or not terms or terms != sorted(set(terms)) \
                        or not all(isinstance(t, str) and re.fullmatch(terminal_pattern, t) for t in terms):
                    raise ValueError('Missing or ambiguous terminal binding.')
            expected_name = 'V:' + '|'.join(column['terminals']) + ':ref=' + '|'.join(column['reference_terminals'])
            if column['name'] != expected_name or index in nodes:
                raise ValueError('Voltage identity differs from its physical binding.')
            nodes[index] = column
        else:
            component = column.get('component')
            if not isinstance(component, str) or not re.fullmatch(component_pattern, component) \
                    or column.get('positive_terminal') != 'XIN:0' or column.get('negative_terminal') != 'YOUT:0':
                raise ValueError('Missing component or current-direction binding.')
            if column['name'] != prefixes[kind] + component:
                raise ValueError('Signal identity differs from its component binding.')
            if kind == 'component_current':
                if component in currents or index in currents.values():
                    raise ValueError('Duplicate component-current binding.')
                currents[component] = index
            elif kind == 'auxiliary_current':
                if index in auxiliary:
                    raise ValueError('Duplicate auxiliary-current binding.')
                auxiliary.add(index)
            else:
                device_groups[kind].add(component)
    if sorted(nodes) != list(range(len(nodes))) or not nodes:
        raise ValueError('Incomplete node bindings.')
    if sorted(currents.values()) != list(range(len(currents))):
        raise ValueError('Incomplete component-current bindings.')
    terminals = [term for node in nodes.values() for term in node['terminals']]
    expected_terminals = {component + ':' + side + ':0' for component in currents for side in ('XIN', 'YOUT')}
    if len(terminals) != len(set(terminals)) or set(terminals) != expected_terminals:
        raise ValueError('Missing, duplicate, or unknown connected terminal.')
    if any(node['reference_terminals'] != nodes[0]['terminals'] for node in nodes.values()):
        raise ValueError('Inconsistent numerical datum binding.')
    if sorted(auxiliary) != list(range(len(nodes), len(nodes) + len(auxiliary))):
        raise ValueError('Incomplete auxiliary-current bindings.')
    for column in columns:
        if column['kind'] not in ('node_voltage', 'component_current'):
            component = column['component']
            if component not in currents or (column['kind'] != 'auxiliary_current' and column['release_index'] != currents[component]):
                raise ValueError('Signal has no matching component binding.')
    if device_groups['device_resistance'] != device_groups['conduction_state'] \
            or not device_groups['gate_command'] <= device_groups['conduction_state']:
        raise ValueError('Unmapped command, resistance, or conduction state.')
    observed_auxiliary = {column['component'] for column in columns if column['kind'] == 'auxiliary_current'}
    if set(currents) != inventory['components'] or observed_auxiliary != inventory['auxiliary'] \
            or device_groups['device_resistance'] != inventory['devices'] \
            or device_groups['gate_command'] != inventory['commands']:
        raise ValueError('Missing or extra signal in the pinned release inventory.')


def validate_trace(directory, circuit, inventory=None):
    check_receipt(directory)
    schema = json.loads((directory / 'schema.json').read_text())
    columns = schema['columns']
    validate_schema(columns, inventory if inventory is not None else pinned_inventory(circuit))
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
            for cell in decimal_row:
                mantissa, exponent = cell.split('e')
                magnitude = abs(Decimal(mantissa))
                if (magnitude == 0 and int(exponent) != 0) or (magnitude != 0 and not 1 <= magnitude < 10):
                    raise ValueError('Non-normalized scientific notation.')
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
                    exponent = int(rounded_cell.split('e')[1])
                    permitted = Decimal(5).scaleb(exponent - 13) + Decimal.from_float(math.ulp(value)) / 2
                    if Decimal.from_float(error) > permitted:
                        raise ValueError('Decimal export exceeds its declared rounding precision.')
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
    build_path = args.evidence / args.build_group / 'build.json'
    build_hash = sha(build_path)
    build = json.loads(build_path.read_text())
    process_identities = set()
    results = {}
    for circuit in selected:
        base = args.evidence / args.run_group / circuit
        repeats = [base / f'observer-{i}' for i in range(1, 4)]
        stock = base / 'stock-1'
        stock_receipt = check_receipt(stock)
        for mode, folder in [('stock', stock), *[('observer', repeat) for repeat in repeats]]:
            receipt = check_receipt(folder)
            runtime = json.loads((folder / 'runtime.json').read_text())
            process_identity = (runtime['jvm_identity'], runtime['start_time_ms'])
            if process_identity in process_identities:
                raise ValueError('Reference processes are not independent.')
            process_identities.add(process_identity)
            if receipt['mode'] != mode or receipt['circuit'] != circuit \
                    or receipt['input_sha256'] != release_input_sha(circuit) \
                    or receipt['build_receipt_sha256'] != build_hash:
                raise ValueError('Process role, input, or build provenance mismatch.')
            command = receipt['command']
            expected_input = str(ROOT / 'src/test/resources/ipes/education' / (circuit + '.ipes'))
            if command[-3:] != [expected_input, str(folder), mode] or sha(command[2]) != build['java_executable_sha256']:
                raise ValueError('Capture command does not match its declared provenance.')
            expected_classpath = [str(build_path.parent / 'helper'), str(build_path.parent / 'stock.jar'), build['classpath']]
            if mode == 'observer':
                expected_classpath.insert(0, str(build_path.parent / 'observer'))
            if command[command.index('-cp') + 1] != os.pathsep.join(expected_classpath):
                raise ValueError('Unexpected release capture classpath.')
            expected_version = re.search(r'version "([^"]+)"', build['java_version']).group(1)
            if runtime['java_version'] != expected_version:
                raise ValueError('Unexpected runtime Java version.')
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
    parser.add_argument('--build-group', default='build-java8-2')
    parser.add_argument('--circuits', nargs='+', choices=list(CIRCUITS))
    parser.add_argument('--output', type=Path)
    analyze(parser.parse_args())
