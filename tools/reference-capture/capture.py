#!/usr/bin/env python3
"""Build and capture the pinned release. This tool never executes the Core backend."""
import argparse
import csv
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

RELEASE = 'b34b9b9d8110374097a1fc5839491e66cff5a39a'
CIRCUITS = {
    'buck_simple': .001, 'boost_simple': .001, 'buckBoost_simple': .001,
    'cuk_simple': .001, 'sepic_simple': .001, 'singlePhase_PWM_converter': .02,
    'thyristor_RL_single': .04, 'thyristor_freeWheelingDiode': .04,
    '2phaseDiodeBridge_AC-Inductor': .04, 'diode_RL_singlePH_trafo': .06,
}
ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
KERNEL = Path('src/main/java/ch/technokrat/gecko/geckocircuits/circuit/SimulationsKern.java')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def release_input_sha(circuit):
    data = subprocess.check_output(['git', '-C', str(ROOT), 'show',
                                    f'{RELEASE}:resources/education_www.ipes.ethz.ch/{circuit}.ipes'])
    return hashlib.sha256(data).hexdigest()


def git(tree, *args):
    return subprocess.check_output(['git', '-C', str(tree), *args], text=True).strip()


def save(path, data):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(data, stream, indent=2, sort_keys=True, allow_nan=False)
        stream.write('\n')


def execute(command, cwd, log, timeout=180):
    started = time.time()
    with log.open('x') as stream:
        process = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise RuntimeError(f'Capture timed out. Inspect {log}')
    result = {'command': command, 'cwd': str(cwd), 'exit_code': code,
              'started_epoch': started, 'elapsed_seconds': time.time() - started,
              'log_sha256': sha(log)}
    if code:
        raise RuntimeError(f'Exit {code}. Inspect {log}')
    return result


def prepare(args):
    stock, observer, evidence, jdk = args.stock, args.observer, args.evidence, args.jdk
    if git(stock, 'rev-parse', 'HEAD') != RELEASE or git(observer, 'rev-parse', 'HEAD') != RELEASE:
        raise ValueError('Both release worktrees must have the pinned commit as HEAD.')
    if git(stock, 'diff', 'HEAD', '--'):
        raise ValueError('Stock release sources are modified.')
    patch = (HERE / 'v202-observer.patch').read_text()
    actual_patch = git(observer, 'diff', '--', str(KERNEL))
    if actual_patch != patch.strip():
        raise ValueError('Observer source does not match the recorded observation patch.')
    for circuit in CIRCUITS:
        current = ROOT / 'src/test/resources/ipes/education' / (circuit + '.ipes')
        original = stock / 'resources/education_www.ipes.ethz.ch' / (circuit + '.ipes')
        if sha(current) != sha(original):
            raise ValueError(f'Input differs from release: {circuit}')
    print('Pinned source, observation patch, and ten input hashes verified.', flush=True)
    if not args.execute:
        print('PREVIEW: compile stock source, capture helper, and two-hook observer with Java 8.')
        return
    build = evidence / args.build_group
    build.mkdir()
    deps = (stock / 'target/capture-classpath.txt').read_text().strip()
    deps += os.pathsep + str(jdk / 'lib/tools.jar')
    stock_classes = build / 'stock'
    helpers = build / 'helper'
    observer_classes = build / 'observer'
    for directory in (stock_classes, helpers, observer_classes):
        directory.mkdir()
    source_list = build / 'stock-sources.txt'
    sources = sorted((stock / 'src/main/java').rglob('*.java'))
    source_list.write_text('\n'.join(str(p) for p in sources) + '\n')
    command = [str(jdk / 'bin/javac'), '-encoding', 'UTF-8', '-cp', deps,
               '-d', str(stock_classes), '@' + str(source_list)]
    build_runs = [execute(command, stock, build / 'stock-compile.log')]
    # Copy resources only. Every stock class comes from the Java 8 compilation above.
    for resource in (stock / 'target/classes').rglob('*'):
        if resource.is_file() and resource.suffix != '.class':
            target = stock_classes / resource.relative_to(stock / 'target/classes')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(resource, target)
    helper_sources = sorted((HERE / 'java').rglob('*.java'))
    build_runs.append(execute([str(jdk / 'bin/javac'), '-encoding', 'UTF-8',
                              '-cp', str(stock_classes) + os.pathsep + deps, '-d', str(helpers),
                              *map(str, helper_sources)], stock, build / 'helper-compile.log'))
    build_runs.append(execute([str(jdk / 'bin/javac'), '-encoding', 'UTF-8',
                              '-cp', os.pathsep.join((str(helpers), str(stock_classes), deps)),
                              '-d', str(observer_classes), str(observer / KERNEL)],
                             stock, build / 'observer-compile.log'))
    build_runs.append(execute([str(jdk / 'bin/jar'), 'cf', str(build / 'stock.jar'),
                              '-C', str(stock_classes), '.'], stock, build / 'stock-jar.log'))
    artifacts = {}
    for folder in (stock_classes, helpers, observer_classes):
        for file in sorted(folder.rglob('*')):
            if file.is_file():
                artifacts[str(file.relative_to(build))] = sha(file)
    artifacts['stock.jar'] = sha(build / 'stock.jar')
    save(build / 'build.json', {
        'release_commit': RELEASE, 'source_count': len(sources), 'runs': build_runs,
        'java_version': subprocess.check_output([str(jdk / 'bin/java'), '-version'], stderr=subprocess.STDOUT, text=True),
        'java_executable_sha256': sha(jdk / 'bin/java'), 'javac_sha256': sha(jdk / 'bin/javac'),
        'patch_sha256': sha(HERE / 'v202-observer.patch'), 'artifacts': artifacts,
        'dependencies': {p: sha(p) for p in deps.split(os.pathsep)},
        'source_hashes': {str(p.relative_to(stock)): sha(p) for p in sources},
        'capture_sources': {str(p.relative_to(HERE)): sha(p) for p in helper_sources},
        'classpath': deps, 'core_comparison_executed': False,
    })
    print('BUILD_COMPLETE', flush=True)


def run(args):
    build = args.evidence / args.build_group
    receipt = json.loads((build / 'build.json').read_text())
    if sha(args.jdk / 'bin/java') != receipt['java_executable_sha256']:
        raise ValueError('Runtime does not match the compiled reference build.')
    if receipt['patch_sha256'] != sha(HERE / 'v202-observer.patch'):
        raise ValueError('Capture patch changed after compilation.')
    for relative, expected in receipt['artifacts'].items():
        if sha(build / relative) != expected:
            raise ValueError(f'Build artifact changed: {relative}')
    for dependency, expected in receipt['dependencies'].items():
        if sha(dependency) != expected:
            raise ValueError(f'Dependency changed: {dependency}')
    for source, expected in receipt['capture_sources'].items():
        if sha(HERE / source) != expected:
            raise ValueError(f'Capture source changed after compilation: {source}')
    selected = args.circuits or list(CIRCUITS)
    for circuit in selected:
        path = ROOT / 'src/test/resources/ipes/education' / f'{circuit}.ipes'
        if sha(path) != release_input_sha(circuit):
            raise ValueError(f'Input no longer matches the pinned release: {circuit}')
    planned = [(circuit, mode, repeat) for circuit in selected
               for mode, count in [('stock', 1), ('observer', 3)] for repeat in range(1, count + 1)]
    print(json.dumps({'preview': not args.execute, 'fresh_process_runs': len(planned),
                      'circuits': selected, 'core_comparison_executed': False}), flush=True)
    if not args.execute:
        return
    for circuit, mode, repeat in planned:
        output = args.evidence / args.run_group / circuit / f'{mode}-{repeat}'
        done = output / 'process.json'
        if done.exists():
            prior = json.loads(done.read_text())
            if prior['build_receipt_sha256'] != sha(build / 'build.json'):
                raise ValueError('Existing capture has a different build.')
            for relative, expected in prior['artifacts'].items():
                if sha(output / relative) != expected:
                    raise ValueError('Existing capture artifact changed.')
            print(f'RETAIN_VALID {circuit} {mode}-{repeat}', flush=True)
            continue
        if output.exists():
            raise ValueError(f'Incomplete existing output retained; choose a new --run-group: {output}')
        output.mkdir(parents=True)
        circuit_file = ROOT / 'src/test/resources/ipes/education' / f'{circuit}.ipes'
        classpath = [str(build / 'helper'), str(build / 'stock.jar'), receipt['classpath']]
        if mode == 'observer':
            classpath.insert(0, str(build / 'observer'))
        command = ['xvfb-run', '-a', str(args.jdk / 'bin/java'), '-Xmx512m',
                   '-Dpolyglot.js.nashorn-compat=true',
                   '-cp', os.pathsep.join(classpath), 'V202CaptureMain',
                   str(circuit_file), str(output), mode]
        result = execute(command, args.stock, output / 'process.log')
        if 'CAPTURE_SUCCESS' not in (output / 'process.log').read_text():
            raise ValueError('Missing capture completion marker.')
        result.update({'circuit': circuit, 'input_sha256': sha(circuit_file), 'mode': mode,
                       'build_receipt_sha256': sha(build / 'build.json'),
                       'artifacts': {p.name: sha(p) for p in output.iterdir() if p.is_file()}})
        save(done, result)
        print(f'CAPTURED {circuit} {mode}-{repeat}', flush=True)


def test_stock(args):
    build = args.evidence / args.build_group
    receipt = json.loads((build / 'build.json').read_text())
    suites = sorted({ET.parse(file).getroot().attrib['name']
                     for file in (args.stock / 'target/surefire-reports').glob('TEST-*.xml')})
    if not suites:
        raise ValueError('No baseline release test suites found.')
    print(f'{"TEST" if args.execute else "PREVIEW"}: compile release tests with Java 8 and run {len(suites)} baseline suites.', flush=True)
    if not args.execute:
        return
    output = args.evidence / (args.test_group or args.build_group + '-tests')
    output.mkdir()
    classes = output / 'classes'
    classes.mkdir()
    sources = sorted((args.stock / 'src/test/java').rglob('*.java'))
    source_list = output / 'test-sources.txt'
    source_list.write_text('\n'.join(str(p) for p in sources) + '\n')
    classpath = os.pathsep.join((str(build / 'stock.jar'), receipt['classpath']))
    commands = [execute([str(args.jdk / 'bin/javac'), '-encoding', 'UTF-8', '-cp', classpath,
                         '-d', str(classes), '@' + str(source_list)],
                        args.stock, output / 'compile.log')]
    for resource in (args.stock / 'target/test-classes').rglob('*'):
        if resource.is_file() and resource.suffix != '.class':
            target = classes / resource.relative_to(args.stock / 'target/test-classes')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(resource, target)
    test_classpath = str(classes) + os.pathsep + classpath
    if args.test_observer:
        test_classpath = os.pathsep.join((str(build / 'observer'), str(build / 'helper'), test_classpath))
    commands.append(execute([str(args.jdk / 'bin/java'), '-ea', '-Dpolyglot.js.nashorn-compat=true',
                             '-cp', test_classpath,
                             'org.junit.runner.JUnitCore', *suites], args.stock, output / 'tests.log'))
    save(output / 'verification.json', {'commands': commands, 'suites': suites,
                                       'build_receipt_sha256': sha(build / 'build.json'),
                                       'core_comparison_executed': False})
    print((output / 'tests.log').read_text()[-500:], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'run', 'test-stock'])
    base = ROOT.parent
    parser.add_argument('--stock', type=Path, default=base / 'm4-v202-stock')
    parser.add_argument('--observer', type=Path, default=base / 'm4-v202-observer')
    parser.add_argument('--evidence', type=Path, default=base / 'm4-reference-evidence-20261123')
    parser.add_argument('--jdk', type=Path, default=Path('/home/tinix/codex-envs/gecko-v202-jdk8'))
    parser.add_argument('--run-group', default='runs')
    parser.add_argument('--build-group', default='build-java8')
    parser.add_argument('--test-group')
    parser.add_argument('--test-observer', action='store_true')
    parser.add_argument('--circuits', nargs='+', choices=list(CIRCUITS))
    parser.add_argument('--execute', action='store_true', help='Mutate new outputs. Default is a read-only preview.')
    args = parser.parse_args()
    globals()[args.action.replace('-', '_')](args)


if __name__ == '__main__':
    main()
