import unittest
from datetime import date

from aggregate import aggregate, expired, window

TODAY = date(2026, 11, 20)


def rep(uid, day, cell="53.70:17.56", typ="airtag"):
    return {"uid": uid, "day": day, "cell": cell, "type": typ}


class T(unittest.TestCase):
    def test_window_is_7_days_ending_2_days_ago(self):
        self.assertEqual(window(TODAY), (date(2026, 11, 12), date(2026, 11, 18)))

    def test_k_anonymity_hides_cell_below_3_devices(self):
        r = [rep("a", "2026-11-15"), rep("b", "2026-11-15"), rep("a", "2026-11-16", typ="tile")]
        self.assertEqual(aggregate(r, TODAY)["cells"], {})

    def test_cell_shown_with_3_devices(self):
        r = [rep("a", "2026-11-15"), rep("b", "2026-11-16"), rep("c", "2026-11-17", typ="tile")]
        c = aggregate(r, TODAY)["cells"]["53.70:17.56"]
        self.assertEqual(c, {"total": 3, "airtag": 2, "tile": 1, "smarttag": 0})

    def test_recent_days_delayed(self):
        r = [rep(u, "2026-11-19") for u in "abc"] + [rep(u, "2026-11-20") for u in "abc"]
        self.assertEqual(aggregate(r, TODAY)["cells"], {})

    def test_bad_rows_ignored(self):
        r = [rep(u, "2026-11-15") for u in "abc"] + [{"uid": "x"}, rep("y", "zły"), rep("z", "2026-11-15", typ="bomba")]
        self.assertEqual(aggregate(r, TODAY)["cells"]["53.70:17.56"]["total"], 3)

    def test_no_device_ids_in_output(self):
        r = [rep(u, "2026-11-15") for u in ("uid-1", "uid-2", "uid-3")]
        self.assertNotIn("uid-", str(aggregate(r, TODAY)))

    def test_expired_and_broken_deleted(self):
        r = [rep("a", "2026-11-11"), rep("b", "2026-11-12"), {"uid": "c", "day": "zły"}]
        self.assertEqual([x["uid"] for x in expired(r, TODAY)], ["a", "c"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
