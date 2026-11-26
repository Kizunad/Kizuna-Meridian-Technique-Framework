"""用生产目录布局验证只读报告及离群对照。"""

import hashlib
from math import inf, log
from pathlib import Path
import shutil
from statistics import pstdev
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
from technique_balance import fit
from technique_catalog import one, parse, read_catalog
from technique_metrics import measures, rows


class BalanceTest(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "shared/src/main/resources/data/sample"
        shutil.copytree(SCRIPTS / "tests/fixtures", self.data)
        self.candidate = self.data / "candidate.txtpb"
        self.original = self.candidate.read_bytes()

    def run_report(self):
        # 通过正常 CLI 写到目录外，检查读过的输入保持原样。
        output = self.root / "out.md"
        before = self.candidate.read_bytes()
        subprocess.run([sys.executable, "-B", str(SCRIPTS / "technique_balance.py"),
                        "--root", str(self.root), "--report", str(output)], check=True)
        self.assertEqual(self.candidate.read_bytes(), before)
        return output.read_text()

    def section(self, report, label):
        return report.split(f"## {label}\n", 1)[1].split("\n## ", 1)[0]

    def test_extreme_and_restoration(self):
        baseline = self.run_report()
        self.assertNotIn("sample:candidate", self.section(baseline, "待定"))
        self.candidate.write_bytes(self.original.replace(b"value: 1 ", b"value: 10 "))
        pending = self.section(self.run_report(), "待定")
        row = next(line for line in pending.splitlines() if "candidate@1" in line and "| M |" in line)
        self.assertIn("| 10 |", row)
        print("倍率 10，退出 0，待定", row)
        self.candidate.write_bytes(self.original)
        self.assertEqual(baseline, self.run_report())
        print("还原后退出 0，参考报告逐字节一致，夹具 SHA-256", hashlib.sha256(self.original).hexdigest())

    def test_compensated_feature(self):
        higher_damage = self.original.replace(b"value: 1 ", b"value: 1.25 ")
        changed = higher_damage.replace(b"warmup_millis: 300", b"warmup_millis: 360")
        self.candidate.write_bytes(changed)
        report = self.run_report()
        self.assertNotIn("sample:candidate", self.section(report, "待定"))
        feature = self.section(report, "特色")
        row = next(line for line in feature.splitlines() if "candidate@1" in line and "| M |" in line)
        self.assertIn("起手 +", row)
        forms = rows(read_catalog(self.root))
        self.assertAlmostEqual(fit(forms)["M"][2]["sample:candidate@1"], 1.5, delta=0.02)
        print("倍率 1.25、起手 360 ms，退出 0，特色", row)
        self.candidate.write_bytes(self.original)
        self.assertNotIn("sample:candidate", self.section(self.run_report(), "特色"))

    def test_book_shift_is_report_only(self):
        # 把原有高端两式组成另一部书，保留每个定位的样本和值。
        path = self.data / "book.txtpb"
        parts = path.read_text().splitlines()
        refs = [line for line in parts if "peer_7" in line or "peer_8" in line]
        other = [parts[0].replace("sample:book", "sample:other"),
                 'volumes { id: "sample:other_volume" book_id: "sample:other"', *refs, "}"]
        path.write_text("\n".join([line for line in parts if line not in refs] + other))
        section = self.section(self.run_report(), "整书偏移")
        self.assertRegex(section, r"sample:other \| M .*🔴")

    def test_parser_discovery_and_versions(self):
        for folder in ("build", "gametest", "wt-copy"):
            ignored = self.root / folder / "src/main/resources/data/sample"
            ignored.mkdir(parents=True)
            (ignored / "duplicate.txtpb").write_bytes(self.original)
        catalog = read_catalog(self.root)
        self.assertEqual((len(catalog.files), len(rows(catalog))), (3, 10))
        self.assertEqual(one(parse('id: "冒号:#保留" # 行尾'), "id"), "冒号:#保留")
        self.candidate.write_text("definitions {")
        error = subprocess.run([sys.executable, "-B", str(SCRIPTS / "technique_balance.py"),
                                "--root", str(self.root)], capture_output=True, text=True)
        self.assertNotEqual(error.returncode, 0)
        self.assertIn("candidate.txtpb", error.stderr)

    def test_cost_damage_and_hold(self):
        form = parse("""
            id: "d:form" version: 1 phases { warmup_millis: 250 recovery_millis: 250
            active_duration: ACTIVE_DURATION_UNTIL_RELEASE } cooldown_millis: 2000
            slots { id: "arm" minimum_count: 2 maximum_count: 3 }
            cost { qi { slot_id: "arm" amount { value: 2 } distribution: EACH }
                   work { slot_id: "arm" power { value: 20 } duration { value: 0.2 } } }
            effects { work { slot_id: "arm" power { value: 10 } duration { value: 0.1 } } }
            effects { guard { maximum_hold_millis: 500 } }
            effects { weapon_strike { dedup_group: "a" multiplier { value: 1 } } }
            effects { weapon_strike { dedup_group: "a" multiplier { value: 1 } } }
            effects { limb_strike { dedup_group: "b" multiplier { value: 2 } } }
            effects { bound_arm_cut { value: 0.2 } }
        """)
        row = measures(form, {}, "v")
        self.assertEqual((row.busy, row.cycle, row.qi, row.work), ((0.5, 1), (2.5, 3), (4, 6), (10, 15)))
        self.assertAlmostEqual(row.metrics["M"], 3.2)
        self.assertEqual(row.share, (0.2, 1/3))
        del form["effects"][1]["guard"][0]["maximum_hold_millis"]
        self.assertEqual(measures(form, {}, "v").busy[1], inf)

    def test_throw_and_pooled_sigma(self):
        form = parse("""id: "d:f" version: 1 phases {} cooldown_millis: 1000
                        effects { grapple { lean { multiplier { value: 0.4 } } } }
                        effects { throw { multiplier { value: 2 } } }""")
        self.assertEqual(measures(form, {}, "v").metrics["M"], 2)
        forms = rows(read_catalog(self.root))
        for i, r in enumerate(forms):
            r.role = "入门" if i < 5 else "功能"
            r.metrics["M"] = (1, 2, 2, 2, 4)[i % 5] * (1 if i < 5 else 10)
        centers, sigma, scores = fit(forms)["M"]
        self.assertEqual(set(centers.values()), {2, 20})
        self.assertAlmostEqual(sigma, pstdev([log(0.5), 0, 0, 0, log(2)]))
        self.assertAlmostEqual(min(scores.values()), -max(scores.values()))


if __name__ == "__main__":
    unittest.main()
