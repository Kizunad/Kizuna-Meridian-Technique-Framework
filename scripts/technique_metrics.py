"""目录声明的单目标伤害、时序、费用和卷级占用。"""

from collections import defaultdict
from dataclasses import dataclass
from math import inf, isfinite

from technique_catalog import one, reference


def quantity(node, key):
    return float(one(one(node, key), "value"))


def number(value):
    if value is None:
        return "—"
    if value == inf:
        return "∞"
    return f"{value:.3f}".rstrip("0").rstrip(".")


def span(bounds):
    low, high = bounds
    if low == high:
        return number(low)
    return f"{number(low)}–{number(high)}"


def costs(form, ops):
    # EACH 按实绑数扣费，均摊费整式一次；逐拍做功和起手做功均按槽逐块结算。
    slots = {one(s, "id"): s for s in form.get("slots", [])}
    cost = one(form, "cost", {})
    qi = [0., 0.]
    work = [0., 0.]
    for entry in cost.get("qi", []):
        each = one(entry, "distribution") == "EACH"
        for i, key in enumerate(("minimum_count", "maximum_count")):
            count = int(one(slots[one(entry, "slot_id")], key)) if each else 1
            qi[i] += quantity(entry, "amount") * count
    for entry in cost.get("work", []) + ops.get("work", []):
        joules = quantity(entry, "power") * quantity(entry, "duration")
        slot = slots[one(entry, "slot_id")]
        for i, key in enumerate(("minimum_count", "maximum_count")):
            work[i] += joules * int(one(slot, key))
    return tuple(qi), tuple(work)


def tempo(book):
    # 兵器决定节奏档，同一本书内加长一式起手不改变分组。
    if one(book, "id") == "meridian_piglin:gold_eating":
        return "另列"
    rules = one(book, "held_item_rules", {})
    tags = rules.get("required_item_tags", []) + [
        tag for category in rules.get("categories", []) for tag in category.get("item_tags", [])
    ]
    if any("sword" in tag or "dagger" in tag for tag in tags):
        return "快兵"
    if "arm_rules" in book or "leg_rules" in book or any("axes" in tag or "hammer" in tag for tag in tags):
        return "重兵"
    return "另列"


STRIKES = ("weapon_strike", "limb_strike", "leg_strike")
SHAPES = (
    ("刺", ("刺", "抖")), ("撩", ("撩",)), ("扫", ("扫", "不留行")),
    ("劈", ("劈", "斩", "破门")), ("擒摔", ("摔", "擒", "缠腕")), ("连击", ("连", "叩门")),
    ("拳", ("拳", "寸劲")), ("腿", ("腿", "蹬", "尥蹶", "踹")), ("冲撞", ("撞门", "掩门")),
)


def shape(name, ops):
    if "launch_projectile" in ops:
        return "远程"
    if "guard" in ops:
        return "格"
    if any(k in ops for k in (*STRIKES, "throw")):
        return next((label for label, words in SHAPES if any(w in name for w in words)), "打击")
    if "use_brew" in ops:
        return "用药"
    if any(k in ops for k in ("organ_refine", "elemental_infusion", "status_benefit")):
        return "法术"
    return "辅助"


def role(name, ops, targets):
    # 规划点名的示例优先，其余按打击效果归入暂定定位。
    if "weapon_bind" in ops:
        return "长控制"
    if name in ("追风直刺", "叩门", "弹腿", "崩拳"):
        return "入门"
    if targets > 1:
        return "群体"
    if name in ("破盾", "寸劲") or any(key in ops for key in ("throw", "grapple", "impulse")):
        return "功能"
    return "常规"


def damage(ops, strikes):
    # 同组对单目标只命中一次，取可能命中的最高一拍；束臂切割与末击可以相加。
    groups = defaultdict(float)
    for strike in strikes:
        group = one(strike, "dedup_group")
        groups[group] = max(groups[group], quantity(strike, "multiplier"))
    total = sum(groups.values()) + sum(float(one(c, "value")) for c in ops.get("bound_arm_cut", []))
    throws = sum(quantity(t, "multiplier") for t in ops.get("throw", []))
    lean = sum(quantity(one(g, "lean"), "multiplier") for g in ops.get("grapple", []) if "lean" in g)
    return total + max(throws, lean)


@dataclass
class Form:
    ident: str
    name: str
    book: str
    volume: str
    tier: str
    shape: str
    role: str
    phases: tuple
    active: tuple
    busy: tuple
    cycle: tuple
    share: tuple
    qi: tuple
    work: tuple
    metrics: dict
    notes: list


def measures(form, book, volume):
    name = one(one(form, "presentation", {}), "display_name", one(form, "id"))
    ops = defaultdict(list)
    for effect in form.get("effects", []):
        for key, values in effect.items():
            if key != "at_millis":
                ops[key].extend(values)
    strikes = [s for key in STRIKES for s in ops.get(key, [])]
    phase = one(form, "phases")
    warm, active, recovery = (int(one(phase, key, 0)) / 1000 for key in
                              ("warmup_millis", "active_millis", "recovery_millis"))
    cooldown = int(one(form, "cooldown_millis")) / 1000
    active_range = (active, active)
    if one(phase, "active_duration") == "ACTIVE_DURATION_UNTIL_RELEASE":
        limit = one(one(ops, "guard", {}), "maximum_hold_millis")
        active_range = (0., int(limit) / 1000 if limit else inf)
    busy = tuple(warm + a + recovery for a in active_range)
    cycle = tuple(b + cooldown for b in busy)
    share = tuple(b / c if c and isfinite(c) else float(c > 0) for b, c in zip(busy, cycle))
    targets = max((int(one(s, "maximum_targets", 1)) for s in strikes), default=1)
    multiplier = damage(ops, strikes)
    qi, work = costs(form, ops)
    metrics = {"M": multiplier, "B": busy[1], "C": cycle[1], "冷却": cooldown, "起手": warm,
               "T": multiplier / cycle[1] if cycle[1] else None,
               "元价": qi[1] / multiplier if multiplier else None, "目标": targets}
    for label, key in (("距离", "distance"), ("角度", "angle")):
        metrics[label] = max((quantity(s, key) for s in strikes if key in s), default=None)
    return Form(f"{one(form, 'id')}@{one(form, 'version')}", name, one(book, "id", "—"), volume, tempo(book),
                shape(name, ops), role(name, ops, targets), (warm, recovery, cooldown), active_range, busy,
                cycle, share, qi, work, metrics, form_notes(form, ops))


def form_notes(form, ops):
    follow = one(form, "follow_up", {})
    notes = []
    for t in follow.get("transitions", []):
        prior = one(one(t, "previous", {}), "id", "条件触发")
        notes.append(f"{prior.split(':')[-1]}→起播 {one(t, 'start_millis', 0)}ms")
    if one(follow, "only_follow_up") == "true":
        notes.append("只能承接")
    if "required_heat_status" in one(form, "held_item_rules", {}):
        notes.append("需热兵")
    if ops.get("throw"):
        notes.append("摔不动改用一靠")
    if ops.get("launch_projectile"):
        notes.append("弹道伤害另计")
    return list(dict.fromkeys(notes))


def rows(catalog):
    result = []
    assigned = set()
    for vid, volume in catalog.volumes.items():
        book = catalog.books[one(volume, "book_id")]
        for ref in volume.get("techniques", []):
            key = reference(ref)
            if key in assigned:
                raise ValueError(f"式重复入卷：{key}")
            result.append(measures(catalog.forms[key], book, vid))
            assigned.add(key)
    for key in sorted(catalog.forms.keys() - assigned):
        result.append(measures(catalog.forms[key], {}, "未入卷"))
    return sorted(result, key=lambda r: (r.book, r.volume))
