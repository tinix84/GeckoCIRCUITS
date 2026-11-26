from unittest import TestCase, main

from scoreboard import capability, summarize


def case(issues=(), status='partial_import', parsed=True):
    attempt = {'status': status, 'parse': {'ipes_parse': 'passed'} if parsed else None, 'issues': list(issues)}
    return {'archive_path': 'example.asc', 'sha256': 'source-hash', 'original_analyses': ['.tran 1u 1m'],
            'attempts': {'selected_profile': attempt, 'legacy_non_ac_without_stock_or_hierarchy': attempt}}


class ScoreboardTest(TestCase):
    def test_partial_parse_does_not_count_as_conversion_or_acceptance(self):
        row = summarize([case()], [])['by_analysis']['.tran']
        self.assertEqual(dict(row), {'declared': 1, 'converts': 0, 'opens_in_core': 1,
                                    'runs_to_completion': 0, 'numerically_accepted': 0})

    def test_capability_counts_analyses_not_issue_instances(self):
        issue = {'code': 'UNSUPPORTED_TRANSIENT_OPTION', 'message': 'uic'}
        report = summarize([case([issue, issue])], [])
        self.assertEqual(report['non_ac_legacy_capability_gaps'], {'.tran options': 1})

    def test_unrelated_hash_cannot_inherit_acceptance(self):
        history = {'archive_path': 'example.asc', 'directive_index': 0, 'sha256': 'wrong',
                   'original_directive': '.tran 1u 1m'}
        with self.assertRaisesRegex(ValueError, 'source differs'):
            summarize([case()], [history])

    def test_behavioural_spike_does_not_automatically_exclude_tier_b(self):
        issue = {'code': 'UNSUPPORTED_SYMBOL', 'component': 'B1', 'message': 'Unsupported LTspice symbol: bv'}
        row = summarize([case([issue])], [])['analyses'][0]
        self.assertEqual(row['tier'], 'B')
        self.assertEqual(row['behavioural_spike_issue'], 162)

    def test_physics_tier_requires_observed_blocker_and_solver_link(self):
        issue = {'code': 'DIODE_MODEL_APPROXIMATION', 'message': 'unsupported exponential behavior'}
        row = summarize([case([issue])], [])['analyses'][0]
        self.assertEqual((row['tier'], row['solver_issues']), ('C', [163]))

    def test_each_declared_analysis_keeps_its_own_identity(self):
        circuit = case(status='complete_import')
        circuit['original_analyses'].append('.ac dec 10 1 100')
        report = summarize([circuit], [])
        self.assertEqual(len(report['analyses']), 2)
        self.assertEqual(report['by_analysis']['.ac']['declared'], 1)


if __name__ == '__main__':
    main()
