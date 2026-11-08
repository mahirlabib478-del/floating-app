import tempfile
import unittest
from pathlib import Path

from floatclip_txt import QuickTextStore


class QuickTextStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.store = QuickTextStore(Path(self.temp_dir.name) / "test.db")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_add_edit_delete(self):
        item_id = self.store.add("Greeting", "Hello there")
        item = self.store.list_items()[0]
        self.assertEqual(item.id, item_id)
        self.assertEqual(item.title, "Greeting")
        self.store.update(item_id, "Updated", "Good morning")
        self.assertEqual(self.store.list_items()[0].content, "Good morning")
        self.store.delete(item_id)
        self.assertEqual(self.store.list_items(), [])

    def test_sequential_copy_wraps_to_first_item(self):
        first = self.store.add("First", "One")
        second = self.store.add("Second", "Two")
        third = self.store.add("Third", "Three")

        self.assertEqual(self.store.next_item().id, first)
        self.store.mark_copied(first)
        self.assertEqual(self.store.next_item().id, second)
        self.store.mark_copied(second)
        self.assertEqual(self.store.next_item().id, third)
        self.store.mark_copied(third)
        self.assertEqual(self.store.next_item().id, first)

    def test_reorder_changes_next_item_relative_to_last_copied(self):
        first = self.store.add("First", "One")
        second = self.store.add("Second", "Two")
        third = self.store.add("Third", "Three")
        self.store.mark_copied(first)

        self.store.reorder([third, first, second])
        self.assertEqual(self.store.next_item().id, second)

    def test_deleting_last_copied_item_restarts_at_first(self):
        first = self.store.add("First", "One")
        second = self.store.add("Second", "Two")
        self.store.add("Third", "Three")
        self.store.mark_copied(second)
        self.store.delete(second)
        self.assertEqual(self.store.next_item().id, first)

    def test_empty_queue_returns_no_next_item(self):
        self.assertIsNone(self.store.next_item())


if __name__ == "__main__":
    unittest.main()
