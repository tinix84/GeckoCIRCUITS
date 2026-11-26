"""Storage admission tests; no files or simulation processes are created."""

from collections import namedtuple
from unittest import TestCase, main
from unittest.mock import Mock, patch

import batch
from storage_guard import FLOOR, require_free_space

Usage = namedtuple('Usage', 'total used free')


class StorageAdmissionTest(TestCase):
    def test_either_drive_below_floor_refuses(self):
        for low_drive in ('C:/', 'D:/'):
            with self.subTest(drive=low_drive):
                with patch('storage_guard.shutil.disk_usage', side_effect=lambda drive:
                           Usage(100 * FLOOR, 0, FLOOR - 1 if drive == low_drive else FLOOR)):
                    with self.assertRaisesRegex(RuntimeError, 'both C: and D:'):
                        require_free_space()

    def test_exact_floor_is_admitted(self):
        with patch('storage_guard.shutil.disk_usage', return_value=Usage(100 * FLOOR, 0, FLOOR)):
            self.assertEqual(require_free_space(), {'C:/': FLOOR, 'D:/': FLOOR})

    def test_unreadable_drive_fails_closed(self):
        with patch('storage_guard.shutil.disk_usage', side_effect=OSError('drive unavailable')):
            with self.assertRaises(OSError):
                require_free_space()

    def test_low_disk_prevents_case_directory_and_process(self):
        with patch('storage_guard.shutil.disk_usage', return_value=Usage(100 * FLOOR, 0, FLOOR - 1)):
            with patch('batch.Path.mkdir') as mkdir, patch('batch.subprocess.Popen') as launch:
                with self.assertRaisesRegex(RuntimeError, 'Storage guard'):
                    batch.run_case({'id': '001', 'output': 'unused.ipes'})
                mkdir.assert_not_called()
                launch.assert_not_called()

    def test_low_disk_prevents_execute_reading_inputs(self):
        with patch('storage_guard.shutil.disk_usage', return_value=Usage(100 * FLOOR, 0, FLOOR - 1)):
            with patch('batch.read') as read:
                with self.assertRaisesRegex(RuntimeError, 'Storage guard'):
                    batch.execute()
                read.assert_not_called()

    def test_low_disk_during_case_stops_owned_process(self):
        process = Mock()
        process.poll.return_value = None
        process.wait.return_value = -1
        with patch('batch.directory_bytes', return_value=0), patch('batch.time.monotonic', return_value=1):
            with patch('batch.storage', side_effect=RuntimeError('Storage guard: low disk')):
                result = batch.monitor_case(process, 0, 'unused-output', 'unused-temp')
        self.assertEqual(result, (-1, False, False, 'Storage guard: low disk'))
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=5)

    def test_unreadable_output_during_case_stops_owned_process(self):
        process = Mock()
        process.poll.return_value = None
        process.wait.return_value = -1
        with patch('batch.directory_bytes', side_effect=OSError('output unavailable')):
            with patch('batch.time.monotonic', return_value=1):
                result = batch.monitor_case(process, 0, 'unused-output', 'unused-temp')
        self.assertEqual(result, (-1, False, False, 'output unavailable'))
        process.kill.assert_called_once_with()
        process.wait.assert_called_once_with(timeout=5)

    def test_case_cap_includes_temporary_bytes(self):
        process = Mock()
        process.poll.return_value = None
        process.wait.return_value = -1
        with patch('batch.directory_bytes', side_effect=[batch.CASE_LIMIT, 1]):
            with patch('batch.time.monotonic', return_value=1), patch('batch.storage'):
                result = batch.monitor_case(process, 0, 'unused-output', 'unused-temp')
        self.assertEqual(result, (-1, False, True, None))
        process.kill.assert_called_once_with()


if __name__ == '__main__':
    main()
