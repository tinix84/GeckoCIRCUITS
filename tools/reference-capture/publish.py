#!/usr/bin/env python3
"""Copy validated release fixtures into a separate provenance namespace."""
import argparse
import json
from pathlib import Path
import shutil

from analyze import check_receipt, decoded_hash
from capture import CIRCUITS, HERE, ROOT, save, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, default=ROOT.parent / 'm4-reference-evidence-20261123')
    parser.add_argument('--analysis', default='reference-validation-1.json')
    parser.add_argument('--build-group', default='build-java8-2')
    parser.add_argument('--run-group', default='runs')
    parser.add_argument('--namespace', default='v202')
    parser.add_argument('--supplement', action='store_true', help='Verify existing fixtures and append a new evidence file without replacing them.')
    parser.add_argument('--evidence-name', default='reference-evidence.json')
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    analysis_path = args.evidence / args.analysis
    analysis = json.loads(analysis_path.read_text())
    if not analysis['all_ten_validated'] or analysis['core_comparison_executed']:
        raise ValueError('This publication requires the release-only checkpoint evidence.')
    if set(analysis['circuits']) != set(CIRCUITS):
        raise ValueError('Missing circuit evidence.')
    target = ROOT / 'src/modules/gecko-simulation-core/src/test/resources/traces' / args.namespace
    if target.exists() and not args.supplement:
        raise ValueError('Existing fixture namespace retained. Review it before another publication.')
    if args.supplement and not target.is_dir():
        raise ValueError('A verification supplement requires an existing fixture namespace.')
    receipts = {}
    build_path = args.evidence / args.build_group / 'build.json'
    build_hash = sha(build_path)
    build = json.loads(build_path.read_text())
    fixtures = {}
    for circuit, report in analysis['circuits'].items():
        reference = Path(report['reference_path'])
        if reference != args.evidence / args.run_group / circuit / 'observer-1':
            raise ValueError('Analysis report belongs to a different run group.')
        if sha(reference / 'trace.csv.gz') != report['trace_sha256'] or sha(reference / 'schema.json') != report['schema_sha256']:
            raise ValueError('Validated reference artifact changed.')
        for name in ('stock-1', 'observer-1', 'observer-2', 'observer-3'):
            receipt = check_receipt(reference.parent / name)
            if receipt['build_receipt_sha256'] != build_hash:
                raise ValueError('Selected build differs from capture process provenance.')
            receipt['jvm_runtime'] = json.loads((reference.parent / name / 'runtime.json').read_text())
            receipts[f'{circuit}/{name}'] = receipt
        if args.supplement:
            trace, schema = target / f'{circuit}_trace.csv.gz', target / f'{circuit}_schema.json'
            if decoded_hash(trace) != decoded_hash(reference / 'trace.csv.gz') or sha(schema) != sha(reference / 'schema.json'):
                raise ValueError('New reference evidence differs from the retained fixture.')
            fixtures[circuit] = {'trace_sha256': sha(trace), 'schema_sha256': sha(schema),
                                 'validated_against_trace_sha256': report['trace_sha256']}
    print(f'{"PUBLISH" if args.execute else "PREVIEW"}: ten traces, ten physical schemas, and provenance evidence to {target}')
    if not args.execute:
        return
    if not args.supplement:
        target.mkdir()
        for circuit, report in analysis['circuits'].items():
            reference = Path(report['reference_path'])
            shutil.copy2(reference / 'trace.csv.gz', target / f'{circuit}_trace.csv.gz')
            shutil.copy2(reference / 'schema.json', target / f'{circuit}_schema.json')
    save(target / args.evidence_name, {
        'publication_mode': 'verification_supplement' if args.supplement else 'new_fixtures',
        'retained_fixture_hashes': fixtures,
        'prior_capture_evidence_sha256': sha(target / 'reference-evidence.json') if args.supplement else None,
        'analysis': analysis, 'analysis_sha256': sha(analysis_path),
        'build': build,
        'build_receipt_sha256': build_hash,
        'runs': receipts,
        'capture_tool_sources': {str(p.relative_to(HERE)): sha(p) for p in (HERE / 'java').rglob('*.java')},
        'patch_sha256': sha(HERE / 'v202-observer.patch'),
        'absolute_tolerances_approved': False,
    })


if __name__ == '__main__':
    main()
