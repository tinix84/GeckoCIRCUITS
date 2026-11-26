"""Storage admission tests; no files or simulation processes are created."""

from collections import namedtuple
from unittest import TestCase, main
from unittest.mock import patch

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


if __name__ == '__main__':
    main()
