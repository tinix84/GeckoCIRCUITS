#!/usr/bin/env python3
"""Copy validated release fixtures into a separate provenance namespace."""
import argparse
import json
from pathlib import Path
import shutil

from analyze import check_receipt
from capture import CIRCUITS, HERE, ROOT, save, sha


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, default=ROOT.parent / 'm4-reference-evidence-20261123')
    parser.add_argument('--analysis', default='reference-validation-1.json')
    parser.add_argument('--build-group', default='build-java8-2')
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    analysis_path = args.evidence / args.analysis
    analysis = json.loads(analysis_path.read_text())
    if not analysis['all_ten_validated'] or analysis['core_comparison_executed']:
        raise ValueError('This publication requires the release-only checkpoint evidence.')
    if set(analysis['circuits']) != set(CIRCUITS):
        raise ValueError('Missing circuit evidence.')
    target = ROOT / 'src/modules/gecko-simulation-core/src/test/resources/traces/v202'
    if target.exists():
        raise ValueError('Existing fixture namespace retained. Review it before another publication.')
    receipts = {}
    for circuit, report in analysis['circuits'].items():
        reference = Path(report['reference_path'])
        if sha(reference / 'trace.csv.gz') != report['trace_sha256'] or sha(reference / 'schema.json') != report['schema_sha256']:
            raise ValueError('Validated reference artifact changed.')
        for name in ('stock-1', 'observer-1', 'observer-2', 'observer-3'):
            receipts[f'{circuit}/{name}'] = check_receipt(reference.parent / name)
    print(f'{"PUBLISH" if args.execute else "PREVIEW"}: ten traces, ten physical schemas, and provenance evidence to {target}')
    if not args.execute:
        return
    target.mkdir()
    for circuit, report in analysis['circuits'].items():
        reference = Path(report['reference_path'])
        shutil.copy2(reference / 'trace.csv.gz', target / f'{circuit}_trace.csv.gz')
        shutil.copy2(reference / 'schema.json', target / f'{circuit}_schema.json')
    save(target / 'reference-evidence.json', {
        'analysis': analysis, 'analysis_sha256': sha(analysis_path),
        'build': json.loads((args.evidence / args.build_group / 'build.json').read_text()),
        'build_receipt_sha256': sha(args.evidence / args.build_group / 'build.json'),
        'runs': receipts,
        'capture_tool_sources': {str(p.relative_to(HERE)): sha(p) for p in (HERE / 'java').rglob('*.java')},
        'patch_sha256': sha(HERE / 'v202-observer.patch'),
        'absolute_tolerances_approved': False,
    })


if __name__ == '__main__':
    main()
