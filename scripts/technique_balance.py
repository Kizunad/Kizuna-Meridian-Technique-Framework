"""按书、卷、式生成只读的功法平衡参考表。"""

import argparse
from collections import defaultdict
from itertools import groupby
from math import exp, isfinite, log
from pathlib import Path
from statistics import mean, median, pstdev
import sys

from technique_catalog import one, read_catalog
from technique_metrics import number, rows, span


PRIMARY = ("M", "T", "B", "元价")
CONTEXT = ("冷却", "起手", "距离", "角度", "目标")
BOOK_SHIFT = 0.5
COVERAGE = (0.60, 0.85)


def table(headers, records):
    # 转义单元格，目录文字不能改变表结构。
    def line(cells):
        return "| " + " | ".join(str(c).replace("|", "&#124;").replace("\n", " ") for c in cells) + " |"

    return [line(headers), line(["---"] * len(headers)), *(line(r) for r in records)]


def distribution(samples):
    centers = {group: median([x for _, x in pairs]) for group, pairs in samples.items()}
    residuals = {r.ident: log(x / centers[group]) for group, pairs in samples.items() for r, x in pairs}
    sigma = pstdev(residuals.values()) if residuals else 0.
    scores = {k: v / sigma for k, v in residuals.items()} if sigma else {}
    return centers, sigma, scores


def fit(forms):
    result = {}
    for key in PRIMARY + CONTEXT:
        samples = defaultdict(list)
        for r in forms:
            x = r.metrics[key]
            if x is not None and x > 0 and isfinite(x):
                samples[r.tier, r.role].append((r, x))
        result[key] = distribution(samples)
    return result


def compensation(r, key, score, model):
    # 高伤可用较长起手、较贵费用或较窄命中条件补偿；反向偏离的取舍也反向。
    costs = ("B", "冷却", "起手", "元价")
    benefits = ("距离", "角度", "目标")
    if key in ("M", "T"):
        trade = [(k, 1) for k in costs] + [(k, -1) for k in benefits]
    elif key == "元价":
        trade = [(k, 1) for k in ("T", *benefits)] + [(k, -1) for k in ("B", "冷却")]
    else:
        trade = [(k, 1) for k in ("M", *benefits)] + [(k, -1) for k in ("冷却", "元价")]
    evidence = []
    for other, direction in trade:
        z = model[other][2].get(r.ident, 0.)
        if z * direction * (1 if score > 0 else -1) >= 1:
            evidence.append(f"{other} {z:+.2f}σ")
    return "、".join(evidence)


def findings(forms, model):
    groups = {"待定": [], "特色": []}
    for key in PRIMARY:
        centers, sigma, scores = model[key]
        for r in forms:
            z = scores.get(r.ident, 0.)
            if abs(z) <= 1 + 1e-12:
                continue
            payment = compensation(r, key, z, model)
            label = "待定" if abs(z) > 2 + 1e-12 and not payment else "特色"
            center = centers[r.tier, r.role]
            band = span((center * exp(-sigma), center * exp(sigma)))
            advice = "先核实补偿，再考虑调式" if label == "待定" else "保留带宽，复核取舍"
            groups[label].append((r.name, r.ident, r.tier + "·" + r.role, key, number(r.metrics[key]), band,
                                  f"{z:+.2f}σ", payment or "未见同组可量化补偿", advice))
    return groups


def regression(forms, model):
    lines = ["## 全目录分布", "",
             "同档同定位取原值中位数 m。每个指标对全部可比式合并估 σ = SD(ln(x/m))；"
             "z = ln(x/m)/σ，中心带为 m × exp(±σ)。只用正的有限值，零元式不参加价格回归。"
             "σ 为零时只列实值。低尾和高尾分别统计低于 −1σ、高于 +1σ 的式数。"
             "较长一尾至少三式且达到另一尾两倍时提示单侧偏。补偿栏列出取舍方向一致且达到 1σ 的指标。", ""]
    summary = []
    roles = []
    for key in PRIMARY:
        centers, sigma, scores = model[key]
        tail = list(scores.values())
        inside = sum(abs(z) <= 1 + 1e-12 for z in tail)
        fraction = inside / len(tail) if tail else None
        note = "—"
        if fraction is not None:
            note = f"{fraction:.1%}"
            if not COVERAGE[0] <= fraction <= COVERAGE[1]:
                note += "（复核带宽）"
        below, above = sum(z < -1 for z in tail), sum(z > 1 for z in tail)
        skew = "—"
        if max(below, above) >= 3 and max(below, above) >= 2 * min(below, above):
            skew = "偏向高尾" if above > below else "偏向低尾"
        summary.append((key, len(tail), number(sigma), note, below, above, skew))
        for (tier, role), m in sorted(centers.items()):
            n = sum(r.tier == tier and r.role == role and (r.metrics[key] or 0) > 0 for r in forms)
            roles.append((tier, role, key, n, number(m), span((m * exp(-sigma), m * exp(sigma)))))
    lines += table(("量", "有效 z 数", "合并 σ", "带内占比（60%–85%）", "低尾", "高尾", "偏斜提示"), summary)
    lines += ["", "### 定位中心和参考带", ""]
    lines += table(("节奏", "定位", "量", "样本", "中位数", "中心 ±1σ"), roles)
    return lines


def shape_distribution(forms, model):
    # 同时保留动作式型切片，定位回归不会把同一种动作假定成同一种用途。
    records = []
    ordered = sorted(forms, key=lambda r: (r.tier, r.shape))
    for (tier, kind), members in groupby(ordered, key=lambda r: (r.tier, r.shape)):
        batch = list(members)
        for key in PRIMARY:
            xs = sorted(r.metrics[key] for r in batch if r.metrics[key] and isfinite(r.metrics[key]))
            if not xs:
                continue
            m, sigma = median(xs), model[key][1]
            low, high = m * exp(-sigma), m * exp(sigma)
            below, above = sum(x < low for x in xs), sum(x > high for x in xs)
            inside = (len(xs) - below - above) / len(xs)
            records.append((tier, kind, key, len(xs), span((low, high)), number(xs[0]), number(m),
                            number(xs[-1]), f"{inside:.0%}", f"{below}／{above}"))
    return ["## 同档同式型分布", "", "各切片按自身中位数和同一合并 σ 展示。单例只能定位。", "",
            *table(("档", "式型", "量", "式数", "±1σ", "最小", "P50", "最大", "带内", "低尾／高尾"), records)]


def book_shifts(forms, model):
    records = []
    for book, members in groupby(forms, key=lambda r: r.book):
        batch = list(members)
        for key in PRIMARY:
            scores = model[key][2]
            values = [scores[r.ident] for r in batch if r.ident in scores]
            if not values:
                continue
            z = mean(values)
            note = "🔴 复核整书" if abs(z) > BOOK_SHIFT else "—"
            records.append((book, key, len(values), f"{z:+.2f}σ", note))
    return ["## 整书偏移", "", "各指标对书内式的定位 z 等权平均。绝对值超过 0.5σ 标红，小样本须连同式数读。", "",
            *table(("书", "量", "式数", "平均偏移", "建议"), records)]


def volume_records(batch):
    records = []
    for r in batch:
        warm, recovery, cool = map(number, r.phases)
        m = r.metrics["M"]
        price = span(tuple(q / m for q in r.qi)) if m else "—"
        records.append((r.name, r.ident, r.shape, r.role if m and r.tier != "另列" else "—",
                        "/".join((warm, span(r.active), recovery, cool)), span(r.busy), span(r.cycle),
                        number(m) if m else "—", number(r.metrics["T"]) if m else "—",
                        span(r.qi), price, span(r.share), span(r.work), "；".join(r.notes) or "—"))
    return table(("式", "ID@版本", "式型", "定位", "起/持/收/冷", "B", "C", "M", "T", "元",
                  "元价", "B/C", "做功 J", "条件与承接"), records)


def catalog_table(catalog, forms):
    lines = ["## 逐卷平衡表", "",
             "时间单位秒。B=起手+持段+收势，C=B+冷却，T=M/C，元价=真元/M，U=Σ(B/C)。"
             "M 取同一目标可命中的目录倍率上限；弹道、状态和普通攻击另计。"
             "持守列立即释放至最长持守；承接起播另注，U 含卷内训练、条件式和持守，未模拟轮转。"
             "做功只计完整执行的 cost.work 与 effects.work，不含持续守费等条件账。"]
    rates = defaultdict(list)
    for vid, members in groupby(forms, key=lambda r: r.volume):
        batch = list(members)
        book = catalog.books.get(batch[0].book, {})
        volume = catalog.volumes.get(vid, {})
        u = tuple(map(sum, zip(*(r.share for r in batch))))
        name = one(volume, "display_name", vid)
        lines += ["", f"### {one(book, 'display_name', '未入书')} · {name}", "",
                  f"{vid} ｜ {batch[0].tier} ｜ {len(batch)} 式 ｜ 整卷 U = {span(u) if volume else '—'}", ""]
        if volume and batch[0].tier != "另列":
            rates[batch[0].tier].append((name, u[1]))
        lines += volume_records(batch)
    return lines + volume_distribution(rates)


def volume_distribution(rates):
    lines = ["", "### 两档整卷 U", "", "每卷一次，短格取最长持守。以本档正值卷中位数为中心，合并两档对数残差估 σ。", ""]
    centers = {tier: median([x for _, x in pairs if x > 0]) for tier, pairs in rates.items()
               if any(x > 0 for _, x in pairs)}
    residuals = [log(x / centers[tier]) for tier, pairs in rates.items() for _, x in pairs if x > 0]
    sigma = pstdev(residuals) if residuals else 0.
    for tier, m in sorted(centers.items()):
        pairs = rates[tier]
        xs = [x for _, x in pairs]
        band = span((m * exp(-sigma), m * exp(sigma)))
        lines.append(f"- {tier} {len(xs)} 卷，U 中位数 {number(m)}，中心带 {band}，实测 {span((min(xs), max(xs)))}。")
        for name, x in pairs:
            if x > 0 and sigma:
                z = log(x / m) / sigma
                if abs(z) > 1:
                    lines.append(f"  - {name} U={number(x)}（{z:+.2f}σ）。建议保留档宽，先核卷内训练和承接占用。")
    return lines


def report(catalog):
    forms = rows(catalog)
    comparable = [r for r in forms if r.tier != "另列" and r.metrics["M"] > 0]
    model = fit(comparable)
    outliers = findings(comparable, model)
    lines = ["# 功法平衡参考报告", "",
             f"正式目录 {len(catalog.files)} 份，{len(catalog.books)} 部书、{len(catalog.volumes)} 卷、"
             f"{len(forms)} 式。快兵与重兵中 {len(comparable)} 式有可比的单目标倍率。", ""]
    for label, out in outliers.items():
        lines += [f"## {label}", ""]
        if out:
            lines += table(("式", "ID@版本", "档与定位", "量", "实值", "中心带", "偏离", "补偿线索", "建议"), out)
        else:
            lines.append("无。")
        lines.append("")
    lines += regression(comparable, model) + [""] + book_shifts(comparable, model)
    lines += [""] + shape_distribution([r for r in forms if r.tier != "另列"], model)
    lines += [""] + catalog_table(catalog, forms)
    lines += ["", "## 非数值问题", ""]
    missing = []
    for (ident, version), definition in catalog.forms.items():
        if ident in ("meridian_story:wind_thrust", "meridian_story:moon_lift") and "ai_hints" not in definition:
            name = one(one(definition, "presentation", {}), "display_name", ident)
            missing.append(f"- {name}（{ident}@{version}）缺少 ai_hints，类人数据选式无法选中。")
    lines += missing or ["无云溪缺项。"]
    lines += ["", "## 正式目录来源", "", *(f"- {p}" for p in catalog.files)]
    return "\n".join(lines) + "\n"


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument("--root", type=Path, default=Path.home() / "Code", help="工作区、单仓或单个工作树")
    args.add_argument("--report", type=Path, help="Markdown 输出路径，缺省写标准输出")
    options = args.parse_args()
    try:
        content = report(read_catalog(options.root))
        if options.report:
            if options.report.resolve().suffix.lower() == ".txtpb":
                raise ValueError("报告不能覆盖 txtpb 目录文件")
            options.report.write_text(content, encoding="utf-8")
        else:
            sys.stdout.write(content)
    except (OSError, ValueError, KeyError, TypeError) as error:
        args.exit(2, f"无法生成参考报告：{error}\n")


if __name__ == "__main__":
    main()
