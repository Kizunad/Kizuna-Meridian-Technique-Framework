# 数据契约与校验

## 持续守式与天生式

`CastPhases.active_duration` 缺省为 `ACTIVE_DURATION_FIXED`，旧三段有限时长保持原行为。
`ACTIVE_DURATION_UNTIL_RELEASE` 必须省略 `active_millis`（显式 0 也拒绝），仍显式填写起手、收势、
可打断规则和冷却；起手加收势须大于 0 且不超过一天，按住的时间不受此上限限制。

持续式须为 `DEFENSE`，AI 角色为 `AI_ROLE_GUARD`，恰有一个 `Guard` 效果且时刻等于起手结束。
`Guard.angle` 是 DEGREE 总角度 `(0, 360]`；`damage_multiplier` 是挡住后的剩余剂量 RATIO `[0, 1]`；
`damage_tags` 是 1–16 个不重复的 `namespace:path` 标签，不带 `#`。伤害类型、来向和身体部位由伤害宿主解释。
其余效果只允许起手内的表现和 CASTER 声音；持续伤害、周期做功、承接与普通辅助调度参数在加载目录时拒绝。
`UNTIL_ACTIVE_END` 表现在收势开始时停止；`UNTIL_RECOVERY_END` 保持至收势结束，取消时两者都停止。
普通打击选择器排除 GUARD，`TechniqueSelector.isOrdinarySupport` 也返回 false。

`ActiveCast.sustained` 保存冻结的 Guard、收势长度及可缺省的首次收势时刻。未收时两个结束时刻为 0，
表示未知；收势后生效末为 `max(起手末, 首次接收时刻)`，收势末为生效末加冻结收势长度。
短按走完起手直接收势，没有守窗。未收不写假冷却；收势后从真实收势末计算，取消从实际终止时刻计算。
持续式全程占用动作，不能通过通用收招或承接提前替换。

`Guard.stamina` 可声明每秒维持费、每点守式减伤的命中费（单位为焦耳）、破防禁守毫秒和再起余力比。
四项均为有限正数，比例至多为 1。未声明的冻结守式沿用原有行为。
身体宿主在实际生效边界用 `TechniqueGuardStamina.hold` 生成游标和肌肉占用，将疲劳明细加入同一身体活动。
耗尽时改用 `exhaust`，同笔确定收势、撤掉肌肉占用并保存按式区分的 `guard_break_ends`。
收势原因可从 `SustainedCast.exhausted` 读取；游标更新不会重发阶段通知。
若松手活动暂遇版本冲突，身体在前进到下一毫秒前用 `pendingGuardRelease(entity, body)`
取得按首次松手时刻生成的同一请求，提交后再推进时间。服务仍负责确认收势并发布通知。

`innate=true` 只替代学习检查，不绕过身体、境界、费用和宿主准入。天生式禁止入卷。
`TechniqueKnowledge.references/contains` 按精确版本合并已学与天生式；已有学习顺序保留，新增天生引用按 ID/版本排序。
`TechniqueService.known()` 返回 `List<KnownEntry>`，其中每项只有 `technique` 引用和 `name`。
天生式和已学式在这个只读列表中具有相同形状；天生式没有学习时刻、没有来源，也不会伪造 `KnownTechnique`。
查询或对天生式调用 `learn` 都不会写入学习存档；原 `TechniqueProgress.known` 仍只存真实学习记录。

天生式可以声明 `innate_unlock`。当前身体须达到该式的 `minimum_realm`，且
`opened_meridian_tags` 中每个标签都至少有一条已开拓的经脉。空条件消息仅检查境界，省略整个消息则出生即知。
知识清单、显式学习与起式共用当前身体的判定。旧学习记录也不能使尚未解锁的式出现。
同模板未解锁时显式学习抛出 `InnateLockedException`，起式返回 `NOT_KNOWN`。伤势和余额只参加施放校验。

`GuardState` 是 Java record，字段为 `bodyId/castId/technique/guard`，不属于 proto、不上线、不存档。
旧存档没有 `sustained`，旧状态消息没有 `end_times_unknown`，两者均保持有限式语义。
新内容启用前客户端须支持未知边界；持续说明暂留 `technique.meridian_technique.sustained_details` 文案占位。

## 周期方块效果、巫药事实与动作速度

- `BlockWork` 声明处理器 ID、非空且不重复的目标方块标签、正数米制距离、推进间隔与持续时间。
  间隔必须为正且不超过持续时间，最后一次事件不得超出施放时长。效果身份由原目录序号和周期序号组成。
- `UseBrew` 声明处理器 ID、非空且不重复的药种 ID 和用法枚举。实际物品消费由认领该处理器的附属执行。
- `TechniqueDefinition.brew_item=22` 声明允许的主手药种、饮用/喷溅物理形态和空副手要求，扣费前检查。
  药瓶规则不能替代武器打击或弓弩持物规则；拒绝返回饮用、喷溅、药种或空副手对应原因。
- `UseBrew.technique` 声明 DRINK/ANOINT/THROW/FAN/MIST 的强度、持续、品质升级、封顶、反胃、残药参数；
  FAN 另须米制范围、角度及目标上限，MIST 须范围。所有倍率有限且有界。
  `preparation_sound` 仅供 ANOINT 的 ARMS/LEGS 分组动作，由用药处理器按实际身体分组播放。

## 擒与摔

- `Grapple` 声明处理器 ID、正数米制抓取距离（≤ 抓取上限）、拉到身前的距离（≤ 抓取距离）、正数擒住时长和可缺省的一靠
  `ThrowLean`；擒住时长不得超出施放结束。不声明一靠时，摔不动的目标既不擒也不靠，这一式别的效果照常走。一靠声明时刻（不晚于擒住时长）、伤害类型、倍率 (0, 10] 和推开强度 [0, 10]。
- `Throw` 声明处理器 ID、正数飞行时长、正数落地距离、最高点 [0, 16] m、伤害类型、倍率 (0, 10]、正数倒地时长和
  非负免摔时长。
- 两种效果都要求所属书声明 `ArmRules`；`GrappleIntent` / `ThrowIntent` 必须携带起手冻结的那份规则。

## 器官炼化

- `OrganRefine` 声明器官槽、经脉槽、配方族、批次间隔和起批窗口。器官槽与经脉槽都至少绑定一个模块，
  经脉槽必须 `require_opened`；配方族是 `namespace:path`，由宿主按器官种类与身体相态解析成具体配方。
- 间隔为正且不超过窗口；批次在 `[at, at + duration)` 内按间隔起，窗口终点本身不再起批，
  因此批数为 `ceil(duration / batch)`。窗口终点不得晚于施放结束。效果身份为 `organ_refine_<原目录序号>_<批序号>`。
- `OrganRefineIntent` 携带起手绑定的器官与经脉 ID、配方族、已按动作速度缩放的批次间隔，
  以及权威身体时钟上的窗口终点。宿主在窗口终点之后不得起新批。
- 功法库只排定起批时刻。施放结束或被打断后不再交付后续批次，并经 `TechniqueCastEnded` 通知附属；
  已交付批次是否做完、取料上限、产物去向和损耗都由宿主按 Framework 器官规则决定。
- `ProjectileWeaponCategory.HELD_SPLASH_BREW` 通过 `LaunchProjectile.brew` 交给附属落地处理器，
  必须声明 THROW、lob、零散布和零落点随机半径。持物事实与事件同次 inspect，框架不执行药瓶消费。
- `HeldItem.brew_kind_id`、`brew_quality`（0–3）、`brew_use` 必须同时提供或同时缺省；空手不能携带巫药事实。
- `allow_off_hand_use_while_casting` 开启时必须声明 `off_hand_use_block_tags`；关闭时不能携带标签，标签不得重复。
  两项冻结进 `ActiveCast` 并随移动 LOCK 下发；UNLOCK 清除放行规则。
- `TissueModifier.tempo_peak` 缺省为 1，显式值采用 RATIO 与其他能力峰值同一校验边界。
  `ActiveCast.tempo_multiplier` 保存起手绑定组织的最慢速度；表现意图携带 `playback_speed_multiplier`。
  所有事件与阶段按速度倒数缩放并向上取整，零时长仍为零。
- `TechniqueFailure.EFFECT_REJECTED` 携带附属或缺失处理器的原因文本。生命周期通知与失败交付见
  [宿主集成](Host-Integration.md#附属周期效果与施放结束)。

协议包为 `kizuna.meridian.technique.v1`，Java 生成类为 `TechniqueMessages`。
`TechniqueCatalog`、`TechniqueProgress` 的 `schema_version` 为 1。
字段删除必须保留编号，未知字段、枚举和算子拒绝，不做静默降级。

`TechniqueEffect.status_benefit` 与 `EffectIntent.status_benefit` 表示数据注册的原版增益状态。
`StatusBenefit` 声明身份、持续毫秒、RGB 颜色与 `LeapProfile`；每次跃步做功引用所属功法的组织槽位，
不重复扣除起手真元。几何参数、时间、动画身份和 AI 条件显式声明并有界校验。
`LeapProfile.maximum_path`（≤16 格）是起点到落点的直线预算：上升与水平共用，下降只受 `maximum_drop` 限制；
`launch_speed`（米/秒，≤40）与 `minimum_flight_ticks`/`maximum_flight_ticks`（1 ≤ 最短 ≤ 最长 ≤ 60）决定飞行 tick。
`minimum_distance`、`maximum_distance`、`fallback_distance`、`flight_ticks` 已停用，保留编号，非零即拒绝。
`LeapMotion` 为客户端跳跃请求及服务端运动回执，客户端请求只允许 `REQUEST`，不能上传落点或速度；
`ready_tick` 是服务端世界时间下可再次起跳的时刻，由 FINISH 回执给出。
`StaggerStatus(entity_id, remaining_millis)` 是服务端发给追踪者和本人的僵直显示回执，取受击与魅惑的最大剩余值，0 表示全部结束；
客户端只用于界面与动作，不参与判定。僵直中起式失败码为 `STAGGERED = 21`，玩家原因「僵直中」。
`interruptible_by_damage` 字段名保留，含义为「起手中受僵直即打断」。
状态注册和世界适配见 [宿主接入](Host-Integration.md)。

`TechniqueRegistry.load(AddonRegistry, List<TechniqueCatalog>)` 全部成功后才返回不可变目录。
`TechniqueProtocol.decodeCatalog` 负责有界解析，随后仍必须调用 `load` 完成语义及外部引用校验。
Fabric 在两端模组初始化时读取 `fabric.mod.json` 的 `custom["meridian_technique:catalogs"]`。
值允许单个字符串或字符串数组，其他类型（包括数组中的非字符串）报错。
路径相对于声明模组根目录，`.txtpb` 是 UTF-8 文本，`.binpb` 是 Protobuf 二进制；不接受其他后缀。
清单直接编码 `TechniqueCatalog`，已有 `schema_version = 1` 即格式版本，无额外包装消息。
文本与二进制文件均最多 1 MiB；默认文本解析器拒绝未知字段，二进制递归拒绝未知字段，版本不符同样拒绝。

发现顺序按模组 ID 字典序确定；同一模组先按数组声明顺序读取清单，再按声明顺序调用目录 entrypoint。
所有候选与 Java 入口合并后才校验跨目录书、卷、精确式版本、前置卷及承接关系，不按加载顺序覆盖。
同 ID 不同式版本仍沿用既有语义；同 ID 同版本重复、书卷身份重复或与式 ID 冲突均拒绝。
任一失败中止启动，不发布部分注册表；错误包含 `模组ID:资源路径` 或入口来源，以及条目下标，
冲突同时指出首次声明。文本语法错误提供原文件行列号。
`TechniqueCatalogs.register(source, addons, catalog)` 支持延后统一校验的代码装配；
`build()` 调用 `TechniqueRegistry.loadSources(...)`，`registry.sources()` 可查询不可变的来源与目录。
清单和初始化期代码目录只读取一次，服务器启动时再合入晚到代码目录并完成整体语义校验。
目录没有热加载机制；模板资源约定和两种 Java 接口见[宿主接入](Host-Integration.md)。

初始化期每卷生成一个 `<namespace>:<卷路径>_scroll` 物品，按模组 ID、清单声明顺序和卷声明顺序登记。
生成身份与已有物品冲突时中止启动；创造栏 `meridian_technique:techniques` 显示「功法」，
按书声明顺序归组，书内保留卷序，图标使用展示的第一卷。未装内容时遵循原版空栏隐藏规则。
物品名组合书 `display_name` 与卷 `display_name`，格式为「书名·卷名」；卷名已经包含书名前缀时只显示一次。
物品语言键为 `item.<namespace>.<卷路径>_scroll`。tooltip 每式一行，写式短名、最低境界和真元费用，
例如「金臂：游炁，金行真元 0.3」；没有费用写「不耗真元」，EACH 费用标「/脉」，未声明境界时只写费用。
语言里有 `technique.<namespace>.<path>.note` 时用「，」接在该行后面，没有这条就不显示；末行列前置卷。
整本书共通的话写在 `book.<namespace>.<path>.note`，只属于一卷的话（比如这一卷从哪儿得来）写在
`volume.<namespace>.<path>.note`，各占一行，缺键不显示。
式短名使用 `technique.<namespace>.<path>`，路径中的 `/` 转成 `.`；缺键时使用
`presentation.display_name`（Protobuf 字段 5），旧目录缺省时显示「未命名招式」，不显示资源 ID。
`presentation.description` 单独保存完整说明。内容包提供 zh_cn/en_us 的式、书、卷和卷轴物品名称；
书与卷语言键分别为 `book.<namespace>.<path>`、`volume.<namespace>.<path>`。

右键学习只在服务端调用 `TechniqueService.learn(entityUuid, volumeId)`，与管理命令共用按卷入口。
成功写入整卷后提示「学会《书名·卷名》：式名列表」，生存模式消耗 1 个，创造模式不消耗。
缺前置卷、整卷已学会、无可用身体或其他学习失败均用中文动作栏提示且不消耗；客户端只预测使用动画。
已学状态仍按精确式版本存入 Framework，卷轴不建立第二份知识记录。

卷轴图标为 `assets/<namespace>/textures/item/<卷路径>_scroll.png`；有图而无模型时自动提供
`minecraft:item/generated` 模型，有图且提供模型时使用内容包模型。缺图时统一回落到
`meridian_technique:item/generic_scroll`，每个物品在当前进程最多警告一次；资源重载重新选择模型和贴图。
阅读背景优先使用 `assets/<namespace>/textures/gui/scroll/<卷路径>.png`，尺寸为 1536×1024；缺失时使用
功法库自带的 `meridian_technique:textures/gui/scroll/generic.png`，并对每个缺失资源只警告一次。
服务端确认整卷学习成功后客户端自动打开只读界面，按书名·卷名显示标题、各式语言键名称和
`presentation.description`、前置卷和「已学会」提示。各式数值从同一目录读取：真元费用、冷却、
起手/出招/收势总时长、持物要求、限制移速与冲刺。EACH 费用标记「元/脉」，不伪造尚未绑定的总费用。
卷末提示 R 进入架势及 `/meridian combat slot set <槽号> <功法>`，Tab 补全已学功法。
ESC、合上按钮、纸面外点击、背包键、右键和使用键均可关闭；后两种绑定跟随玩家设置。
正文使用原版字体，不加粗，式间留空行。正文按固定逻辑画布换行，并裁切在纸面中间视口内，
背景完整等比绘制，标题与正文均限定在原图中央公共留白区（横向 320–1216、纵向 176–848 像素）内。
鼠标滚轮可浏览较长内容。背景只影响阅读展示，不作为学习前置。

合上控件使用 `assets/meridian_technique/textures/gui/scroll/close_button.png`，96×72 纵向三帧，
依次为常态、悬停、按下；不使用原版按钮皮肤，文字与无障碍提示仍使用原版控件输入语义。
素材由 CPA `gpt-image-2.5-flare` 生成纸签原图，去品红背景、裁切、最近邻缩为 96×24，
悬停/按下仅派生亮度。提示词与原稿记录在远程交接目录 `handoff/scroll-screen-work/`。

Framework 的标签是开放字符串，没有标签注册或模板枚举 API。
每份目录至少声明一个直绑模板（`binding_templates`，精确 ID 与版本）或模板组（`binding_template_groups`，组 ID）。
组清单由公共 JAR 的 `META-INF/kizuna_meridian/technique_template_groups.list` 发现，每行填写本 JAR 内的一条资源路径。
登记词表来自所有直绑模板和各组基准模板，分别汇集经脉、组织与器官的标签。
学式、学卷、整书学习、起式和知识查询均使用直绑模板与各组成员的并集。起式先检查当前身体模板，
越界返回 `BODY_TEMPLATE_MISMATCH`，再按实际构型绑定各槽。整卷或整书有一式越界即拒绝整批学习。
换身后保留原始学习记录，知识列表、天生式与接续提示按当前身体过滤。
`TechniqueKnowledge.references/contains` 和 `TechniqueCastTimelines.status` 接收当前身体快照。学习记录的存储格式沿用原协议。
类型和真元属性用 `AddonRegistry.findSymbol` 检查身份与种类。

| 数据 | 单位、范围与约束 |
|---|---|
| `Quantity` | 显式 `value` 和 `unit`，有限数；缺失、错单位均拒绝 |
| 真元费用 | `QI`（元），[10⁻¹², 10⁹]；按 `matching` 匹配，保留库存属性与密度；`EACH` 按每条绑定经脉扣 `amount`，`EVEN` 在全部绑定经脉间平摊总 `amount`，`BY_BALANCE` 按可付余额比例分摊总 `amount` |
| 组织做功 | 力 `NEWTON`：[0, 10⁹]；功率 `WATT`：(0, 10⁹]；时间 `SECOND`：(0, 86400]；`training=true` 只允许模板中声明 `TissueTraining` 的组织 |
| 器官做功 | 功率与时间同上，不允许力字段，疲劳由 Framework 唯一预算计算 |
| 最低组织力量 | 可省略；提供时必须是 `NEWTON`，[0, 10⁹] |
| 三段时长 | 显式毫秒，各段非负，总和 (0, 86400000]；每段均可达 9 s；打断布尔必须显式提供 |
| 冷却 | 显式毫秒，[0, 86400000]；仅 `FINISHED` 从原定收势结束算，`CANCELLED/INTERRUPTED` 从实际终止时刻算（含晚恢复） |
| 槽位 | 最多 16 个；标签全部匹配且不重复，0–16 个；标签和类型至少提供一种；按模块 ID 字典序选择 |
| 绑定数量 | 显式最少 [0, 16]、最多 [1, 16]，最少不大于最多；最少 0 表示可选 |
| 费用槽位 | 真元可从必选且 `require_opened=true` 的经脉内腔支付，也可从一个必选器官缓存支付。器官付款槽须指定 `kind`，数量上下限均为 1。做功须绑定必选组织或器官，训练做功须命中可训练组织 |
| 效果时刻 | 显式相对起手毫秒，非负且不超过收势结束；非递减排序，同刻按声明顺序 |
| 组织修正 | 复用 Framework `CurvePoint`；2–16 点，首点偏移 0，之后严格递增，首尾值 0，值 [-1, 1] |
| 修正期限与倍率 | 曲线不超过 300000 ms；四项峰值 `RATIO`：(0, 10]；各点倍率不低于 0.05 |
| 冲量 | 前、后、上、朝目标、`INPUT`；速度 `METERS_PER_SECOND`：(0, 100]。朝目标取施放目标的水平方向；起手时没有选中实体、或和目标水平重合（踩在它头顶）的，宿主按面朝的方向推进，不因此让施放失败；起手时有目标、中途目标消失，施放照旧取消 |
| 表现 | 命名空间资源 ID；一次性或持续到收势结束；剪辑起播 `start_millis`：[0, 86400000]，缺省 0 |
| 移动限速 | `TechniqueDefinition.movement_speed_multiplier=14` 为可选 double，[0,1]，缺省 1；起手至收势有效，0 表示站定且不缩放冲量 |
| 施放中右键 | `TechniqueDefinition.allow_use_while_casting=15` 为 bool，缺省 false：宿主在施放中拦下玩家右键（用物品、放方块、与方块或实体交互），与左键同处同条件；需要右键的式（如服药）写 true 放行 |
| AI 提示 | 距离 `METER`：[0, 1024]，最小不大于最大；HP/真元阈值、战斗情境权重为 `RATIO`：[0, 1] 或 [0, 100]；腿力门槛为 `NEWTON` |
| 熟练度 | `RATIO`：[0, 1]，仅保存供未来 AI 使用，不改变费用、时长或效果 |
| 目录规模 | 最多 1024 个精确功法版本；单目录最多 1024 个模板引用；每功法真元/做功各最多 32 项；效果中非声音效果最多 32 项，`sound` 不计入、另计最多 64 项 |
| 传输与存档 | 载荷最多 1 MiB，解码嵌套最多 32 层；进度含 Any 包装最多 64 KiB |

`TechniqueCatalog.books=5`、`volumes=6` 表达书和卷。书、卷和式共用命名空间 ID，
书卷身份不能重复或与式冲突。全目录书、卷各最多 1024 个，名称可省略，提供时最长 4096 字符。
卷引用已有书和非空的精确式版本列表；每个式版本至多属于一卷，同卷也不能重复。
前置卷只能来自同书，不得重复、自引用、未知或形成环。未分卷的既有式仍可直接学习和施放。
`TechniqueRegistry.books/volumes/findVolume/bookOf` 提供不可变分组查询；归属按精确版本解析。

书可省略 `held_item_rules` 表示不要求持物。提供时类别、耐久表各为 1–32 项：

| 持物字段 | 约束及含义 |
|---|---|
| `categories.item_tags` | 自上而下首次命中，单项标签任一匹配；仅末项必须为空，兜底其余物品 |
| `selection_priority` | 显式非负整数，越小越优先；不同致伤类别可共用优先级 |
| `base_damage_source/base_damage` | `ITEM_ATTACK_DAMAGE` 用 `RATIO`，(0, 10]；`FIXED_DAMAGE` 用 `DAMAGE_POINT`，[0, 10⁹] |
| 致伤、破盾、劈门 | 显式 `CUTTING/BLUNT`、`NO_SHIELD_BREAK/BREAK_BEFORE_HIT/COOLDOWN_AFTER_BLOCK` 和 `can_break_doors` |
| `durability` | 自上而下首次命中，仅末项为空标签兜底；倍率 `RATIO`，(0, 10]；只用于有耐久物品 |
| `selection_order` | 类别优先级、耐久倍率、主手各出现一次；主手必须最后，前两项次序由书声明 |
| 物品标签 | 每项最多 256 个，不重复，使用命名空间 ID；由宿主数据包解释，不读取物品注册表 |

相同类别优先级下，耐久倍率越小越优先；无耐久物品按一次性排在有耐久物品之后，
相同条件选主手。装着东西的物品不参与选手；双手空返回 `HELD_ITEM_REQUIRED` /「需要手持物品」，
无可用物品返回 `HELD_ITEM_UNUSABLE` /「这件物品不能拿来劈」。失败发生在扣费前。
这里只保存伤害、耐久、破盾和劈门声明并选手，不计算实际伤害、不扣耐久、不消耗物品、不执行打击或劈门。
首版没有受保护物品标签。切割工具和钝击工具可拆成不同标签类别并使用相同选手优先级。

`TechniqueDefinition.held_item_rules` 存在时完整覆盖所属书的 `HeldItemRules`，包括类别、耐久、选手顺序、
弹药和整把消耗策略。单式可以用 `required_item_tags` 进一步限制最终选中的物品标签，空列表表示不增加限制。
`TechniqueEffect.consume_held_item` 只能与 `CONSUME_SELECTED_STACK` 配对。起手冻结选定手和策略，
调度器只生成 `ConsumeHeldItemIntent`，不在功法库内直接改物品栏。

承接式的 `FollowUp.blocked_attack_sources` 非空时，只接受攻击者自己身体进度里的 `BlockedAttack`。
事实只能由可信伤害解析通过 `recordBlockedAttack` 写入，来源为盾、通用格挡或硬物之一，事件 ID 用于回执去重，
时间必须不晚于已结清的身体时钟。窗口是阻挡时刻起 600 ms，包含第 600 ms，超过 1 ms 即失效；`StrikeReport` 只能统计结果，
不能创建或替代该事实。承接成功与活动提交原子清除匹配事件，普通起手不清除。卸载、断线、死亡和恢复入口都会清理事实。
不同攻击者的事实分别保存在各自 `TechniqueProgress`，不能跨身体承接。

`TechniqueEffect` 增加 `weapon_strike=5` 和 `break_blocks=6`；有这两类效果的式必须归属于声明持物规则的书。
本库校验参数、选定手位并调度，意图携带起手保存的 `held_item_rules`，宿主在每击时匹配当前物品。

| 打击与劈门字段 | 单位、范围与约束 |
|---|---|
| `weapon_strike.shape/distance` | `SINGLE_TARGET/SECTOR`；距离 `METER`，(0, 1024] |
| `angle/facing_offset` | `DEGREE`；角度 (0, 360]，朝向偏移 [-180, 180]；相对打击时朝向 |
| 单体 | `maximum_targets=1`、`angle=60`，先射线首个生物，再在后备锥内取最近目标 |
| `multiplier/knockback` | `RATIO`；伤害倍率 (0, 10]，击退 [0, 10]；击退换算归宿主 |
| `maximum_targets/cast_target_limit` | 显式整数，1 ≤ 每击上限 ≤ 整次不同目标上限 ≤ 128；各击整次上限一致 |
| `dedup_group` | 必填，1–256 位局部标识 `[a-z0-9_.-]+`；同次施放内同组每目标一次，不同组可重复命中 |
| `can_two_hand/two_hand_multiplier` | 显式开关，倍率 `RATIO`，(0, 10]；不可双手时必须为 1 |
| `break_shield` | 显式开关；启用后结合物品类别的 `shield_interaction`，不自行改变类别 |
| `shield_break_millis/shield_cooldown_millis` | 显式毫秒，[0, 86400000]；不破盾时两者为 0；分别用于先破盾及挡后冷却 |
| `injury_override` | 可省略，提供时只能为 `CUTTING/BLUNT`；缺省沿用物品类别 |
| `last_strike/durability_base` | 显式开关与基数 [1, 1024]；仅整式最后一次打击为 true，各击耐久基数一致 |
| `break_blocks.forward_distance/width` | `METER`，(0, 1024]；相对当前朝向的前方距离和宽度 |
| `block_tags/maximum_doors` | 1–256 个不重复命名空间标签，任一匹配；显式最多扇数 [1, 128]，上下半门算一扇 |

同次施放内按 `dedup_group` 去重：横扫两半圈使用同组，连门三刀各自使用不同组，允许同一目标挨三刀。
全部组仍共用 `cast_target_limit`，按不同目标的并集计数；跨组再次命中已有目标不额外占名额，满额时仍可重击。
`TechniqueStrikeTargets.select(strike, hitIdsByGroup, candidates)` 是纯预算 helper：
宿主传入当前 cast 的 `Map<String, Set<String>>`（组名 → 已命中 ID 集合）及完成几何、阵营筛选的近远有序候选，
得到本击允许的目标；helper 不修改传入映射或集合。本库不保存命中记录，宿主须将执行结果、该组新增命中与回执原子记录。
新 cast 使用独立记录，不能把其他 cast 的命中传入。缺失、空白、非法或超长组名在目录和意图边界均拒绝，
不提供旧目录的默认组；旧 helper 的单集合参数已改为分组映射，调用方需同步迁移。
末击挥空也按当前选定手物品结算一次耐久或消耗；起手打断没有末击，不扣耐久，分组不增加耐久结算次数。
真实伤害、附魔、盾、耐久和权限规则见 [宿主流程](Host-Integration.md)。

阶段 8 的 `TechniqueEffect.leg_strike=20` 与持物打击共用单体/扇形和目标预算，`StrikeFacing` 额外提供
`STRIKE_BACKWARD` 身后扇形。`LegStrike` 必须显式声明 `FRONT_LEG/REAR_LEG/BOTH_LEGS`、命名空间伤害类型 ID、
击退方式与大小、被盾挡住是否仍击退、`last_strike` 和 `dedup_group`；同一范围组每个目标只命中一次，
全部组共享 `cast_target_limit`。`LegRules` 书级规则固定声明忽略手持物品、写明要不要着地（`require_grounded` 必须显式写，true 或 false），以及基础伤害、每点靴子护甲加成和每式耐久消耗。
腿法书不能同时声明 `held_item_rules`。

`TechniqueHostFacts` 与身体、双手在同一次 `inspect` 回执中提供 `grounded/in_water/climbing/riding/gliding`，
并可成对提供 `boot_armor/boot_durability`。书写了 `require_grounded=true` 的腿法起手在水中、攀爬、骑乘、滑翔或未着地时返回
`NOT_GROUNDED` /「要站在地上才能出腿」。`EffectIntent.leg_strike=20` 携带起手保存的 `LegRules`，不携带手位规则。
腿击不能用 `LIFT` 击退，掀起须改用 `limb_strike`。

`TechniqueEffect.limb_strike=25`（`LimbStrike`）是拳掌与腿共用的打击。1–14 号字段与 `LegStrike` 同号、同名、同类型，
其中 `leg=8` 与新的 `arm=15`（`ArmSelection` 的 `FRONT_ARM/REAR_ARM/BOTH_ARMS`）同在 `oneof limb` 里，必须二选一。
`LegKnockbackKind.LIFT=6` 只给肢击用，须另填 `lift_speed=16`（`METERS_PER_SECOND`，正值），水平击退大小可为 0；
其他击退方式不能填 `lift_speed`。其余约束与腿击相同：单体/扇形、显式朝向、盾规则、`dedup_group`、
同组每个目标只命中一次、全部肢击共享 `cast_target_limit`、只有最后一段写 `last_strike=true`。
同一式不能同时有 `leg_strike` 和 `limb_strike`。

17 号起是只有肢击才有的字段，都可以不写；腿击换成的肢击这几项都是缺省：

| 字段 | 含义与约束 |
|---|---|
| `oneof part`：`fixed_part_id=17` / `armed_forearm=18` | 打哪个部位。`fixed_part_id` 是目标身体上的部位 ID（局部标识，左右按目标自己算）；`armed_forearm` 只接受 `true`，表示对方拿着东西那一侧的前臂，两手都拿或都空取主手一侧。两个都不写时照伤害类型的部位组由伤害附属选 |
| `penetration=19`（`Penetration`） | 透劲：`organ_share`（透进器官的份额）和 `armor_multiplier`（只对这一下生效的局部护甲倍数），都是 `RATIO`、[0, 1]，两项都要写 |
| `attachments=20`（`StrikeAttachment`） | 命中附带，和这一下的伤害同一笔加到目标身上；最多 4 条，同一种只能有一条。现在只有 `numb`（`Numb`）：打中部位上的肌肉力量和功率乘 `multiplier`（`RATIO`，[0.05, 1)），持续 `duration_millis`（(0, 300000]）；`whole_arm=true` 时连同这条手臂其余部位上的肌肉 |
| `disarm=21` | 只接受 `true`：打中以后卸掉对方主手拿的东西 |

`TechniqueLimbStrikes.direct(strike)` 判断这一下走哪条路：指定了部位、带透劲或带命中附带时为真，宿主对绑了身体的目标
走可信指定部位的直接伤害（不吃无敌帧、不击退，盾由宿主先查）；没绑身体的目标照旧走普通受伤入口，
透劲只剩护甲打折、麻由内容附属另行处理。功法库只校验数据，不解释部位和肌肉。

出拳掌的肢击要求所属书声明 `TechniqueBook.arm_rules=11`（`ArmRules`）：必须显式写 `ignore_held_items=true`、
显式的 `require_grounded`（true 或 false）和正的 `base_damage`（`DAMAGE_POINT`）。出腿的肢击仍要求书级 `leg_rules`。
拳掌书和腿法书都不能声明 `held_item_rules`。书写了 `require_grounded=true` 时，拳掌起手的着地条件和腿法相同，拒绝码也是 `NOT_GROUNDED`，
只出拳掌的式玩家文本为「要站在地上才能出拳」；同时出腿的式用腿法文本。拒绝不扣费。
`ActiveCast.arm_rules=31` 在起手时保存书级拳掌规则，`EffectIntent.limb_strike=24`（`LimbStrikeIntent`）
携带打击、`hit_sounds=3`，以及出力肢对应的一份书级规则（`leg_rules=2` 或 `arm_rules=4`，不能两份都带），不带手位。

出腿、出拳掌、擒摔的功法里，方向为 `UP` 的冲量当作蹬地起跳：到期那一刻施放者不站在地上（未着地、水中、攀爬、骑乘、滑翔）就不交付这一条，同刻的水平冲量照常交付，免得半空里再跳一次。站在地上时照旧。别的功法（兵器、射击等）的 `UP` 冲量不受这条限制。

腿击意图可以直接换算成肢击意图：`TechniqueLimbStrikes.fromLeg(LegStrike)` 与 `fromLeg(LegStrikeIntent)`
按线格式重读，可选字段有没有填写原样保留，宿主可以用同一个执行器处理两种意图。
`leg_strike` 本身照旧发出 `EffectIntent.leg_strike=20`。

`TechniqueEffect.sound=8`（`SoundCue`）在 `at_millis` 时刻播放一个声音事件；五脏诀设计稿 plans/18 §9.2、§11
里叫 `sound` 的效果就是它。

| 声音字段 | 单位、范围与约束 |
|---|---|
| `sound_id` | 必填，命名空间资源 ID 语法 `[a-z0-9_.-]+:[a-z0-9_./-]+`；不要求服务端注册表里存在 |
| `volume` | `RATIO`，(0, 4]；省略为 1 |
| `pitch` | `RATIO`，[0.5, 2]；省略为 1 |
| `pitch_jitter` | 可省略，`RATIO`，[0, 0.5]；每次播放在 `pitch ± pitch_jitter` 内均匀取值，两端仍须落在 [0.5, 2] |
| `anchor` | 必填，`CASTER`（施放者位置）或 `TARGET`（本次打击真正造成伤害的每个目标位置） |

`TARGET` 必须跟在一条同一 `at_millis` 的 `weapon_strike`、`leg_strike` 或 `limb_strike` 之后，中间只能隔其他同刻声音。
调度时它并入该击意图的 `WeaponStrikeIntent.hit_sounds=3`，不单独成为意图；挥空、被盾挡下或目标免疫时不响。
挥空也要有声音时，在目录里另写一条同刻的 `CASTER` 声音；代码里没有「挥空换声」的特殊分支。
`LeapProfile.takeoff_sound=18` 是跃步起跳声，锚点只能是 `CASTER`，每次实际起跳播放一次。
`EffectIntent.sound=16` 只承载 `CASTER` 声音，效果身份仍是 `effect_<index>`。
目录不声明声音类别：服务端按施放者取，玩家为 `PLAYERS`，原版 `Enemy` 为 `HOSTILE`，其余为 `NEUTRAL`，
详见 [宿主流程](Host-Integration.md#声音)。

`Presentation.sound_id=4` 已废弃：字段号保留，目录里必须为空，非空即拒绝。声音一律用 `SoundCue` 声明，
不存在两套声音声明。

合法但不在服务端声音注册表里的 ID，例如纯数据功法书在自己的 `assets/<ns>/sounds.json` 里声明的事件，
在服务器启动、目录合并后各记一行 WARN。播放时以 `Holder.direct(SoundEvent.createVariableRangeEvent(id))` 直接下发，
客户端有该资源就能听到。

`TechniqueDefinition.follow_up` 由后式声明精确前式版本，最多 32 条，不重复、不自引且引用必须存在。
`recovery_start_ratio` 必须为 `RATIO` [0, 1]；`after_recovery_millis` 显式为 [0, 86400000]；
`start_millis` 显式非负且严格小于后式起手时长。设计稿的后半收势及结束后 0.5 s 分别填 0.5、500。
承接窗口包含两端，从 `active_end + ceil(recovery_duration × recovery_start_ratio)` 到
`recovery_end + after_recovery_millis`。当前施放存在时只看该施放，否则仅 `last_cast.ending=FINISHED`
可提供窗口；取消、打断和装载清理不制造承接机会。

`CastRequest.entry=AUTO`（缺省）在合法窗口自动承接，否则普通起手；显式 `FOLLOW_UP` 窗外返回
`FOLLOW_UP_UNAVAILABLE`。`only_follow_up=true` 单独起手返回 `FOLLOW_UP_REQUIRED`，附玩家口吻原因。
没有合法窗口且另有当前施放时仍返回 `ALREADY_CASTING`，**收招段除外**：当前施放的出招段已过、效果都出完了
（最后一次出效果再过 100 ms，表现不算），别的式可以直接起手，不用等收势走完。这样起手和承接一样原子替换前式：
前式记成 `FINISHED`、冷却照它原定的收势结束算、持续表现收到 STOP；后式从头播，不跳剪辑。
只能承接的式（`only_follow_up`）不走这条。学会、资源、身体、冷却和旧曲线限制始终检查。
生物的选式（`TechniqueSelector`）不用收招段，施放中仍然只考虑承接。

**输入缓冲**（`TechniqueInputBuffer.press`，玩家按键入口）：被 `ALREADY_CASTING`、`COOLING_DOWN`、
`FOLLOW_UP_REQUIRED`、`FOLLOW_UP_UNAVAILABLE` 拒绝、且离能出手不到 `input_buffer_millis`（`config/meridian-technique-feel.toml`，
默认 400，0 关闭，上限 2000）的按键由服务端记着，只记最后一次，每 tick 用新的 `cast_id` 再试，能出手的那一 tick 放出来。
「能出手」取这一式自己的冷却、和当前施放到能接它的时刻（有承接窗口取窗口打开，没有取收招段开始）里晚的。
被打出僵直、死亡、断线、又按了别的键、等过窗口时作废；等过头或换成别的拒绝原因时才把原因发到动作栏。
判定仍全部在 `TechniqueService.cast`，这里不采信客户端的任何时间。

**施放状态同步**（`meridian_technique:cast_status`，服务端 → 施放者本人，`CastStatus`）：起手、结束、预备变化、按键被拒时各发一条。
内容是相对起手的毫秒：三段边界、各次打击、收招段从哪一刻开始（`free_millis`）、本人会的能接的式和各自窗口、
服务端记着的那一式、这次被拒的式和失败码。只是显示副本（施放条、槽位提示），不参与判定；客户端按世界时间自己往下走。
承接提前结束前式收势，保留其原定冷却与曲线；后式起手、生效、收势边界均按起播偏移前移。
原效果时刻小于起播偏移的预备效果不发，包含组织修正；其余效果减去偏移。
修正默认在起手完整原子提交；显式 `AT_EFFECT_TIME` 的修正按投影后的效果时刻登记。
跨过起播点的持续表现是例外：在新起手发 START，剪辑起播为原起播值加已跳过时长；一次性预备表现不补发。

`QiCost.matching` 缺省为 `EXACT_VARIANT`，保留旧目录的精确属性、精确相态支付规则。
`ANY_ATTRIBUTE_AT_LEAST_PHASE` 必须将 `attribute` 留空，`phase` 为最低可用相态；
例如最低 `FREE` 允许气态库存支付，最低 `GAS` 不接受游离态库存。所有模式都必须声明有效相态，未知匹配模式拒绝。
`ATTRIBUTE_AT_LEAST_PHASE` 固定属性为已登记的 `attribute`，相态不低于 `phase`；无属性和其他属性的库存都不能抵价。
付不了时返回 `INSUFFICIENT_ATTRIBUTE_QI`，其余模式仍返回 `INSUFFICIENT_QI`；回执说明写出缺的真元、
要多少和可付多少，见下文「拒绝说明」。
任意属性与指定属性支付都按相态由低到高、同相态按属性 ID 排序取足费用，可以合计同一经脉内的多种相态库存。
每一项扣除和释放都保留原始属性与实际密度，不发生相态转换，也不按密度放大或缩小元数。
取用复用 Framework 最小流转单位与可见减法限制；不能足额支付就拒绝整次起手，不提交部分费用。

每式可引用一个器官付款槽，多项费用按目录顺序从该器官缓存累计扣除。器官和所属部位须都有剩余 HP。
各分配方式在单器官上的应付额均为声明的 `amount`。起式预检与提交使用同一绑定，原库存的实际成分随活动释放。
其他器官、经脉和加工中原料无法代付。重复的成功施放请求复用原回执。

`QiCost.distribution` 必须显式填写。`EACH` 适合用标签和数量把槽位收窄到具体经脉；`EVEN` 适合用宽标签和较大 `maximum_count` 选中全部候选经脉。
`EVEN` 使用 Framework 的 `double` 真元数量和最小可流转单位：经脉 ID 按字典序排列，除最后一条外使用相同份额，最后一条承担浮点余数，因此总扣费等于声明的 `amount`。
若末份仅因浮点尾差低于最小单位，则取最小单位后仍须使顺序合计严格等于 `amount`，否则拒绝。
目录对允许的每个绑定数量和起手扣费共用同一分配规则，Framework 的最小单位校验保持不变。
每条经脉都先单独预检余额，任一经脉不足就拒绝整次施放，不在经脉之间挪补。扣费后的不平衡由 Framework 的 `PassiveQiDiffusion` 自然扩散，本库不再次分配。

`BY_BALANCE` 的 `amount` 是这一项的总费用，按各绑定经脉可付余额（按 `matching` 过滤、每条不低于最小流转单位）的比例分摊。
可付余额合计不低于 `amount` 就放，余额为零的经脉不分摊；例如只有脏经有本行真元、腑经为空时由脏经独付。
余额最大的经脉（同额取 ID 较小者）承担比例份额的取整余数，其余经脉份额低于最小流转单位时跳过。
目录不对 `BY_BALANCE` 做逐绑定数量的平摊预检，余额是否足够只在起手时按实际身体判断。

同一功法的**每条**修正必须满足：
`effect.at_millis + curve.last.offset_millis <= warmup_millis + active_millis + recovery_millis + cooldown_millis`。
生效延迟也计入期限；等于正常冷却结束合法。提前终止可能使冷却早于曲线结束，
进度 `curve_ends` 保留已提交曲线最晚到期时间；同式跨版本要等冷却和曲线都到期才可重施。
本库功法目录采用首尾归零约束。
旧 `tissue_modifier` 的完整曲线契约不变。`StatusBenefit.tissue` 是独立的可撤销生命周期：
状态宿主通过只含 `add_modifiers/remove_modifiers` 的受限活动管理自己登记的
`meridian_technique:status/` 修正。只移除快照中实际存在的自有 ID，禁止移除任何 `cast/` 曲线。
刷新以同笔活动撤销旧组并登记新组；Framework 拒绝时保留旧组，不发布无能力的成功状态。
`StatusBenefit.leap=4` 保持原字段号，`tissue=5` 与其恰有一种。
`TissueBenefitProfile.modifiers` 复用 `TissueModifier` 的组织槽、四项倍率与首尾归零曲线，
每条曲线末点必须等于状态默认时长；重复槽、相对手臂槽、空描述或混合能力均拒绝。
组织状态不采用 `TissueModifier.activation` 的施放登记语义，该字段必须为缺省值；
实际登记只发生于原版效果被接受时，起手中断不会留下待生效的状态曲线。
Framework 接受常值曲线的能力不等于本目录必须接受任意修正数据。
不同功法的曲线可指向同一组织，实际乘积和总上限归 Framework。
`TissueModifier.activation` 缺省为 `AT_CAST_START`，施放起手把每个绑定组织的
`CapabilityModifier` 与费用、做功、动作和进度放进同一份 `BodyActivity`。
显式选择 `AT_EFFECT_TIME` 时，晚于起播点的曲线只保存在 `ActiveCast.pending_modifiers`，
到效果时刻才与清除待办、更新 `curve_ends` 原子提交；同刻做功及世界效果在登记之后执行。
起播点上的曲线仍随起手提交，起播点之前的曲线不补发。
登记前终止施放会丢弃待办，已经登记的曲线完整运行；不生成任何提前移除操作。
迟到登记保留原绝对拐点和结束时刻：只登记当前插值与剩余曲线，不回写过去的身体时钟；
已经到期的曲线只清除待办。未登记曲线可用于只读能力预测，不能当作实际能力。
所有修正来源为
`meridian_technique:caster`，并使用 `meridian_technique:cast/<cast_id 的 ASCII 小写十六进制>/<序号>`
形式的有效内容 ID。每个原始字节固定编码为两位，稳定保留大小写差异；例如 `A` 为 `41`、`a` 为 `61`。
新动作实例 ID 为 `technique.<相同编码>`，可随进度保存并在收势时准确结束。
原始 `cast_id`、幂等键和已保存的修正、动作 ID 不变；新施放统一采用此编码。
新增的路径层级与动作前缀也区分旧格式，避免旧存档的原始 ID 恰好等于新编码时发生碰撞。
由于 Framework 的 `DefinitionId` 只允许一个命名空间分隔符，序号使用路径分隔符而不是第二个冒号。
起手取消、卸载和下线都不生成 `remove_modifiers`；Framework 在曲线到期时自动清理。
协议没有提前撤销算子，宿主请求的活动若含 `remove_modifiers` 也会拒绝。
目录倍率和时长是固定上限；阶段 3 仍须处理 Framework 部署配置更严格时的拒绝。

`TechniqueBinder.bind(BodySnapshot, TechniqueDefinition)` 接收已加载目录中的定义，返回带身体 ID/revision 的绑定，
或具体 `TechniqueFailure` 和一句中文原因。它检查生存、境界、开拓、损伤、疲劳与当前有效力量。
结果不缓存；revision 改变后重新绑定。可选槽位允许空结果，但费用不能借可选槽位免除。

`CastRequest.direction` 只含世界坐标的水平 x、z，两个分量必须有限。
`INPUT` 不使用玩家局部坐标；客户端或战斗 UI 负责先按视角把按键方向转换为世界方向。
非零值仅表达方向，不决定速度或其他数值。当前保留原值以供幂等比较。
`Impulse.input_default_direction` 只允许在 `INPUT` 上声明 `FORWARD` 或 `BACKWARD`，未声明默认前方。
缺省和全零输入由调度器改成这一方向；非零输入保持 `INPUT` 和原方向，交给效果宿主。

`TechniqueHostRequest` 使用标准小写 `entity_uuid` 和非空 `request_id`。
`expected_revision` 只用于 `activity`，由宿主按 Framework 当前 revision 做原子校验；查询和效果操作必须为 0。
oneof 编号固定为 `inspect=10`、`activity=11(BodyActivity)`、`effect=12(EffectIntent)`。
`inspect=false`、缺操作、错误 UUID、非法嵌套活动或效果均拒绝。
`EffectIntent` 声明冲量、表现、移动限速、`weapon_strike=13`、`break_blocks=14`、`launch_projectile=17`、
`target_lock=18`、`leg_strike=20` 或 `limb_strike=24`，交给宿主执行；不包含身体修正。
每条意图带起手固定的 `selected_hand`，无持物规则为未指定；`effect_id` 在施放内独立且稳定。
调度器给每条意图填 `technique=8`，即请求里的精确式引用；旧存档里的承接清理意图可能没有，
宿主要容忍缺省。填了就要有 ID 且版本大于 0。
持物打击和劈门意图必须有选定手和书级规则；腿击和肢击不带手位，只带出力肢对应的书级规则。`scheduled_millis` 为非负的权威身体时刻，迟到 tick 不修改原生效时刻。
表现 STOP 与移动 UNLOCK 始终保留原定收势边界作为 `scheduled_millis`，使提前终止、承接和恢复重试的载荷一致；
它们是收到即执行的清理，宿主不得等待该计划时刻。
目录世界效果用原效果索引 `effect_<index>`；表现停止用表现子列表索引 `presentation_stop_<index>`；
移动限速分别为 `movement_lock/movement_unlock`。身份不依赖 tick 分组，相同时间或相同内容的效果仍各自幂等。
表现开始带剪辑 `start_millis`，`STOP` 的起播时刻必须为 0；本库不读取动画长度。
`TechniqueHostResponse` 以原 `request_id` 回显未认领、身体快照、活动 revision、效果接受或 Framework 失败。
身体分支必须同时携带 `held_items=2` 与 `inspect_facts=20`：前者是显式主副手消息和 `LEFT/RIGHT` 主手侧，后者是移动状态及成对鞋事实。
空手使用无任何字段的 `HeldItem`；非空物品必须提供 ID、标签以及显式的 `damageable/contains_items`。
主副手是逻辑身份，左撇子仍按 `MAIN_HAND/OFF_HAND` 选手，物理左右由宿主解释。
这是宿主接线的必需更新，缺少双手事实会拒绝回执，不能按空手降级。
`TechniqueHostDirectory` 通过 `meridian_technique_host` 字节入口寻找唯一认领者；无人认领由 `TechniqueService.cast` 返回 `NO_HOST`，多个认领停止并报告接线错误。

阶段 3 的 `TechniqueScheduler` 是无状态纯函数：调用方给出上一次已分发的身体时刻和当前已结算时刻，
返回区间内的世界效果 `EffectIntent`。`TechniqueCasting.advance(host, previousMillis)` 在收势边界
额外提交停止动作的 `BodyActivity`，并在 `AdvanceResult.recoveryCommit` 返回终止回执。
僵直打断、卸载和下线也使用这个带游标的结果入口；已到达分发游标的表现均返回 `STOP`，包括一次性表现。
正常收势停止持续表现；倍率小于 1 的施放始终生成 UNLOCK 清理。表现声明、选手与倍率保存于 `ActiveCast`，
目录卸载后仍能清动作、停止表现并解锁。旧存档没有新增声明时无法补造清理意图；
旧 `ActiveCast` 没有 `cooldown_millis` 时保留原冷却值，新施放统一采用收势冷却。

`TechniqueDefinition` 原 `lock_movement=12` 与 `ActiveCast.lock_movement=10` 已删除并 reserved 编号与名称；
没有正式内容继续使用布尔锁。`ActiveCast.movement_speed_multiplier=16` 固定本次倍率。
`MovementIntent.movement_speed_multiplier=2` 随 LOCK 下发，UNLOCK 不携带倍率。
右键放行同样起手冻结：`ActiveCast.allow_use_while_casting=17` 保存目录值，`MovementIntent.allow_use_while_casting=3`
只随 LOCK 下发，旧存档缺省为拦截。宿主以 LOCK 到 UNLOCK 为施放中，左键一律拦，右键按此字段决定，
所以只有会下发 LOCK 的式（倍率小于 1）才拦原版输入。
保留 LOCK/UNLOCK 枚举值与 `movement_lock/movement_unlock` 效果身份，LOCK 现在表示应用正数限速。
宿主按施放 ID 派生 UUID，以 `MULTIPLY_TOTAL=(倍率-1)` 施加临时属性，冲量不乘此倍率。

阶段 4 的 `TechniqueSelector` 先检查成功绑定、可支付、冷却和距离，再按类别权重、情境函数和熟练度
计算权重。候选按功法精确版本稳定排序后使用调用方提供的 `Random` 抽样；零权重或没有候选返回空。
熟练度只影响这一权重，不改变费用、曲线和施放时长。
选择器的外部可用性数据应把 `cooldowns` 与 `curve_ends` 的最大值作为同式可再次施放时刻，
并在 `affordable` 中反映持物等宿主预检；施放入口仍会再次校验。
宿主读候选时可用 `TechniqueAvailability` 的两项只读预检，都要先完成身体绑定，再读同一快照，不提交任何候选：
- `affordable(body, definition, binding)`：用起手同一扣账计算检查全部真元费用；
- `workable(body, definition, binding)`：按不承接、从头起手的边界，以起手同一顺序检查起手做功和全部时间线做功，
  包括力量、功率、耐力、器官余力，以及本式起手登记的修正曲线。
  在同一快照上不承接起手时，做功预检不过的式一定起不了手，走到做功这一步会以 `INSUFFICIENT_CAPABILITY` 拒绝；
  承接会跳过前段做功，预检只会比它更严。
  预检通过不代表一定能起手，冷却、持物、动作占用和部位伤势仍由施放入口判定。
`CombatContext` 提供可信 `TechniqueProgress` 及 `TARGET_SHIELDING/SURROUNDED/UNDER_ATTACK/PURSUED/TARGET_BEHIND/TARGET_AIRBORNE/SELF_FLEEING` 事实，
`AiHints.combat` 的对应可选权重为 `RATIO` [0, 100]，缺省乘 1、零权重排除，多事实相乘。
`minimum_leg_force` 可设两腿有效力量下限；`maximum_warmup_under_attack_millis` 可设挨打时的起手上限；`long_warmup_millis` 必须和
`maximum_long_warmup_distance` 同时提供，分别使用毫秒 [0, 86400000]、`METER` [0, 1024]。
起手达到长起手门槛且目标超出该距离时排除；承接使用扣除起播偏移后的起手长度。
有当前施放或只能承接的候选必须存在合法窗口，进度中的冷却和曲线到期同样参与筛选。
`TechniqueSelector.score(context, known)` 返回最终分数，不可用为 0；`choose` 复用同一分数抽样。
宿主负责事实、绑定、可支付性和敌我判断，本库不依赖 Goal/Brain，也不观察实体。

`AiHints.support` 可缺省；设了 `role` 的式是辅助式，不进 `TechniqueSelector` 打击选式，由宿主大脑按角色施放。
角色必须显式：`AI_ROLE_PREPARE` 要求类别为 `BUFF`，`AI_ROLE_RECOVER` 要求 `RECOVERY`，`AI_ROLE_REPOSITION` 不限类别。
`order` 为同角色先后，小者先放，[0, 100]，缺省 0。`minimum_fatigue_ratio` 为 `RATIO` [0, 1]，
所绑定肌肉的平均疲劳比达到此值才放。`requires_melee_weapon` 要求主手为近战武器。
`refresh_before_expiry_millis` 为毫秒 [0, 86400000]，本式给予的状态剩余不超过此值时才补放。
距离与真元门槛沿用 `AiHints` 的 `minimum_distance`、`maximum_distance`、`minimum_qi_ratio`。

宿主 AI 预判别人的施放用只读入口，不写状态：

- `TechniqueService.observe(uuid)` 返回对方身体与当前施放预报；没有宿主或宿主拒绝查询时为空。
- `TechniqueForecasts.forecast(registry, progress, nowMillis)` 给出阶段、起手剩余毫秒、是否可被僵直打断，
  以及尚未命中的打击（距命中毫秒与目录打击形状）。命中时刻与调度器同一投影：
  起手时刻加偏移减剪辑起点，剪辑起点前的打击不发。没有施放、已过收势或该版本不在目录时为空。
  只有起手期且 `ActiveCast.interruptible_by_damage` 为真时可打断。
- `TechniqueForecasts.firstStrike(definition)` 给出普通起手后的第一次打击；承接只会更早，按普通起手估是保守值。
- `TechniqueForecasts.firstHit(definition)` 回答「这一式会不会打人、最早哪一刻、多远够得着、要不要近身」，
  宿主 AI 认打击只看它，不按效果类型各写一份。兵器、腿、拳掌（`limb_strike`）取各自的 `distance`，
  擒（`grapple`）取 `maximum_distance`，这四种都要近身；投射物取 `ai_hints.maximum_distance`，不用近身；
  摔接在擒后面，不单独算；别的效果不算打人，全是这些的式为空。
- `TechniqueHeldItems.find(rules, items)` 与 `select` 同一规则选手；没有可用手时为空，不抛拒绝。

## 阶段 7：射击与相对手臂

射击只声明意图，纯 Java 库不依赖物品、实体、弹道或具体身体模板。
`ProjectileWeaponCategory` 为 `BOW/CROSSBOW`。`TechniqueDefinition.projectile_weapon=16`
声明每式所需武器，所有 `TechniqueEffect.launch_projectile=9` 必须与之相同。
远程式必须归属于含对应 `HeldItemCategory.projectile_weapon=9` 的持物书；类别不能是末项空标签兜底。

书级 `HeldItemRules.ammunition_tags=4`、`require_ammunition=5`、
`other_hand_empty_or_ammunition=6` 分别规定弹药标签、起手弹药要求与另一手限制。
类别级 `HeldItemCategory.ammunition_tags=10` 非空时覆盖书级标签，支持弓只接受箭、弩另接受烟花。
`TechniqueDefinition.require_ammunition=17` 显式提供时覆盖书级要求；false 只免弹药检查，不免武器或另一手条件。
同类武器双手都有时优先主手；若书要求另一手为空或弹药，两手同时拿武器仍拒绝。

同次 inspect 的 `HeldItems` 提供可选字段 `main_hand_projectile_weapon=4`、
`off_hand_projectile_weapon=5`、`can_find_ammunition=6`。
远程施放必须显式提供双手类别，非弓弩填 UNSPECIFIED；要求弹药时必须提供弹药事实。
目录标签和宿主类别须同时命中；漏报是契约错误，不按标签兜底。
弹药事实由宿主计入原版查找顺序、创造、无限与生物规则，库不自行搜索背包。
四个业务失败为 `BOW_REQUIRED=23`、`CROSSBOW_REQUIRED=24`、
`OTHER_HAND_AMMUNITION_REQUIRED=25`、`AMMUNITION_REQUIRED=26`，文本分别为
「这一式需要弓」「这一式需要弩」「另一只手要空着或拿着箭」「没有箭」。
阶段 8 的 `NOT_GROUNDED=22`、`inspect_facts=20` 保持不变。

| 射击字段 | 单位、范围与约束 |
|---|---|
| `projectile_weapon` | 弓或弩，必须等于式级声明 |
| `speed_multiplier/damage_multiplier` | 显式 Quantity，RATIO，(0, 10] |
| `critical/piercing/spread` | 暴击与穿透显式；穿透整数 [0, 127]；散布 DEGREE，[0, 180] |
| `look` | oneof 方向，必须为 true；取撒放时视线 |
| `look_offset` | DEGREE，[-180, 180]，正值向右；`mirror_by_hand=true` 时副手偏航取反 |
| `lob` | `LobProjectile`：落点最大距离 METER (0, 1024]、半径 METER [0, 最大距离]、仰角 DEGREE (0, 90]、最大速度倍率 RATIO (0, 10] |
| `converge` | 引用后续平射的原剪辑时刻、初速倍率、显式到达延迟、普通抛射 fallback 和最小仰角 |
| `target_snap_angle` | 可省略；DEGREE [0, 180]，目标方向两侧的半角；横风每个 18° 扇区填 9° |
| `multiply_draw_strength/draw_strength_arm` | 开关显式；true 时选 OTHER_ARM 或 WEAPON_ARM，false 时不得指定手臂 |
| `shield_cooldown_millis` | 显式，[0, 86400000]，被盾挡住后的冷却 |
| `mirror_by_hand` | 显式；仅 look_offset 可为 true |

`ConvergeProjectile.reference_shot_millis=1` 指向同式中更晚的一发 `look` 平射；
`reference_speed_multiplier=2` 必须与该平射一致，武器类别、拉弦开关和手臂也须匹配。
`arrival_delay_millis=3` 为 [0, 86400000]；`fallback=4` 为普通抛射参数且规定速度上限，
`minimum_elevation=5` 为 DEGREE (0, 90]。参考时刻不是到达时刻；飞行时间由宿主计算。
汇聚式须启用 `lock_target=18` 并提供 `target_lock_distance=19`（METER，(0, 1024]）。
关闭锁定时不得留下距离字段。

`LaunchProjectileIntent` 携带原射击声明、起手书级规则、`cast_started_millis=3` 和
`clip_start_millis=4`。参考平射身体时刻为
`cast_started_millis + reference_shot_millis - clip_start_millis`；
汇聚本发必须早于该时刻。`EffectIntent.scheduled_millis` 始终保留本发原定身体时刻。
起手先发一次稳定身份 `target_lock`，其 `TargetLockIntent.maximum_distance` 来自快照；
后续效果的 `lock_target=7` 供宿主复用锁定目标。即使没有限速，也在成功起手时立即提交锁定。

`TechniqueSlot.relative_arm=9` 使用 `RelativeArmSlot(arm, left_module_ids, right_module_ids)`，
适用于远程式组织槽；左右集合不得重叠、重复或超过 16 项，分别满足槽位最少数量，
每个 ID 须在引用模板中匹配槽位标签和类型。内容明确列出身体的组织身份，库不解析 ID 前缀。
`TechniqueHeldItems.find/select(rules, items, definition)` 是公开的同规则预判与选手入口。
先选手，再调用 `TechniqueBinder.bind(body, definition, selectedHand, mainHandSide)`，
绑定结果供费用、动作占用和组织曲线共同使用；两参数 bind 在缺少相对手位时返回 INVALID_REQUEST。
OTHER_ARM 取持武器的另一条解剖臂，左右主手和主副手组合都参与换算。

跨弓弩承接须成对声明 `FollowUp.previous_projectile_weapon=5` 和 `next_projectile_weapon=6`，
且匹配精确前后式类别。仍逐条引用前式，沿用原窗口和原子替换；新起手重新检查当前武器与弹药。
同类承接可省略类别字段。库不负责交换物品栏。

`CombatAiHints` 新增距离档（字段 12）、不可见/移动/被逼近/有弹药/有备用武器权重（字段 13–17），
权重均为 RATIO [0, 100]；阶段 8 的字段 8–11 保留。
`TechniqueSelector.CombatContext` 可携带 `Optional<TargetContext>`，包含距离档、Visibility、
目标速度 m/s [0, 100]、Approach、Ammunition 与不可变备用武器类别集合。
缺少远程观察时远程式不入候选；各项观察只乘一次权重，不重复放入离散 CombatFact。
`CombatAiHints` 字段 18–20 是目标正在施放、已倒地、披甲的权重（RATIO [0, 100]），对应离散事实
`TARGET_CASTING/TARGET_DOWNED/TARGET_ARMORED`，由宿主观察后放进 `CombatContext`，各乘一次权重。
`Situation.effectiveLegForceNewtons` 不变。

持物打击可声明 `WeaponStrike.main_hand_damage_type_id=18` 与 `off_hand_damage_type_id=19`。
必须成对且均为完整命名空间 ID；该单手模式不能启用 can_two_hand。
世界执行器直接读取对应 ID，其他既有持物声明的执行规则不变。

`cost.work` 保持起手一次支付。分段费用使用 `TechniqueEffect.work=10`，其 `at_millis` 是该段开始事件，
`WorkCost`、Framework `ActivityEffort` 和 `TissueCapabilities.Exertion` 均不增加未来起始偏移。
开始时刻及做功持续区间不得超出收势结束。起手用当前身体连续预检全部未跳过段，包含整条已登记曲线；
预检不模拟未来恢复、不支付未来段，也不保证中途受伤后仍然付得起。

到每段开始，重新读取身体，校验整段力量、功率、耐力和训练资格；同刻多段与已付序号用同一笔
Framework 活动提交，成功后才交付同刻世界效果。单笔组织/器官费用仍最多 32 项，不同时刻分别检查。
`ActiveCast.paid_work_sequences=20` 保存原始 `effects` 下标，严格递增；活动身份为
`technique_work_<cast_id>_<首个原始下标>`。即使提交回执丢失，身体中的已付序号也阻止重复费用。
原起手真元不变，取消不退已付费用，未开始段直接丢弃；到时付不起会停止施放并清理世界约束。

承接跳过起播点之前的费用，恰好在起播点开始的段随起手支付；后续段减去剪辑偏移。
迟到 tick 按原事件边界依次付费、交付效果；每次费用以宿主当前身体时刻为准，不回溯身体时钟。
效果重试不能越过未确认的边界；回调取消须先处理，再决定是否继续下一段。
恢复入口取消残留施放，不补执行离线期间的费用或射击。

## 拒绝说明、卷轴掉落与状态提示

### 拒绝说明

`CastOutcome.reason` 和 `BindingOutcome.reason` 写给玩家看，说清缺的是哪一样；失败码不变，宿主仍按码区分。
部位名取身体快照的 `display_name`，最多列 4 处，再多写「等 N 处」；名字为空时写「某处」。

| 情形 | 失败码 | 说明 |
|---|---|---|
| 境界不够 | `REALM_TOO_LOW` | 「境界不够：这一式要真炁，你现在是游炁」 |
| 经脉未开 | `MERIDIAN_NOT_OPENED` | 「经脉未通：左肺经、右肺经，还要开通 1 条」，数目为槽位下限减去可用条数 |
| 经脉断了 | `MODULE_UNAVAILABLE` | 「左肺经已断，这一式用不了」 |
| 器官或组织 HP 为零、所在部位 HP 为零 | `MODULE_UNAVAILABLE` | 「左肺、右肺都伤了，这一式用不了」，只有一处时写「伤了」 |
| 组织疲劳达到上限 | `MODULE_UNAVAILABLE` | 「左肱二头肌力竭」 |
| 组织可用力量低于槽位下限 | `INSUFFICIENT_CAPABILITY` | 「左肱二头肌力量不够」 |
| 身体没有带这些标签的部位 | `MISSING_MODULE` | 「这具身体没有这一式要用的器官」，按槽位种类写经脉、器官或筋肉 |
| 真元不够 | `INSUFFICIENT_ATTRIBUTE_QI` / `INSUFFICIENT_QI` | 「金行真元不够：要 0.3，可付 0.12」；要的额向上、可付额向下取两位 |
| 组织出不了这份力 | `INSUFFICIENT_CAPABILITY` | 「左肱二头肌出不了这份力：要 20 W × 0.5 s」 |
| 器官没有余力 | `INSUFFICIENT_CAPABILITY` | 「左肺没有余力：要 10 W × 2 s」 |

同一槽位里有多种不可用原因时，按排序后第一个不可用候选的原因，列出同一原因的全部部位。
器官余力在起手时用 Framework `OrganCapabilities.workBudget` 预检，规则和活动提交相同，预检不过不扣任何费用。
其他失败仍用通用说明，例如冷却中「气息尚未平复」。

### 卷轴掉落

`TechniqueVolume.scroll_drops=6` 声明生存来源，每项 `ScrollDrop{entity_type=1, chance=2}`：
实体 ID 须是 `namespace:path`，同卷不重复，概率在 (0, 1]，每卷至多 16 项；不合格时目录加载报出卷和 `scroll_drops[i]`。
服务器启动时实体未注册直接中止启动，不静默失效。

`TechniqueVolume.scroll_item=7` 省略或为 true 时照常生成 `<卷路径>_scroll` 物品。写 false 时这一卷没有卷轴物品，
也不能声明 `scroll_drops`（目录加载报错），只能经 `TechniqueService.learn` 等服务端入口学会，例如由内容附属在
剧情或交互里调用。

Fabric 端用 `LootTableEvents.MODIFY` 只往该实体自带的掉落表（`EntityType.getDefaultLootTable()`）加池，
数据包替换过的表不动。每项一个池，掉一份本卷卷轴，三个条件都满足才掉：
被玩家杀死（`minecraft:killed_by_player`）、`minecraft:random_chance` 等于声明概率、
`meridian_technique:knows_volume`（字段 `volume`，死者学会了本卷全部式）。
`knows_volume` 只读 `this_entity`，在掉落那一刻问 `TechniqueService.known(uuid)`；没有功法宿主、服务未起或卷未知都判不会。
数据包也可以在自己的表里写这个条件。

### 状态提示

鼠标指向背包里带组织修正的状态图标时，客户端按目录和原版剩余时间出说明，不读身体。
效果名和通用剩余时间由界面框架提供，功法库只提供后面的灰字：

```
金臂
00:20
两臂力量 ×1.25（最高 ×1.25 到第 20 s，第 24 s 归零）
第 4 s，剩 20 s
```

每条修正每项不等于 1 的峰值一行（力量、功率、耐力、恢复、动作速度），当前倍率按 Framework 的
`1 + (峰值 − 1) × 曲线值` 算；括号里写最高值保持到第几秒、曲线低于零的一段落到多少、何时归零。
部位名来自语言键 `effect.<namespace>.<状态路径>.<slot_id>`，缺键时不加部位前缀，不露出语言键。
总时长取目录时长与剩余时间的较大者。用原版命令给出非目录时长、又在中途查看时，服务端按给出时的时长拉伸曲线，
客户端不知道当初给了多久，提示的相位可能与实际不同；功法施放给出的状态总是目录时长，不受影响。
Meridian UI 初始化时把功法库说明来源登记到界面框架；原版背包效果长条不再承担悬停接线。

## 五行附行

`TechniqueEffect.elemental_infusion=24` 声明一个附行。附行生效以后，主人每次可附伤的命中都另打一记本行伤害。
哪些命中可附伤、附伤落在哪里、附带的状态怎么挂，都由伤害附属决定；本库保存状态、算出附伤数值，并管主人身上的附行图标。

| 字段 | 规则 |
|---|---|
| `attribute`、`damage_type_id` | 本行属性 ID、附伤用的原版伤害类型 ID，都是 `namespace:path` |
| `ratio` | RATIO，(0, 10]，附伤占主击剂量的比例 |
| `curve` | 偏移单位 ms，相对效果时刻；2–16 点，首点偏移 0，首尾值 0，偏移严格递增，值在 [0, 1]；末点就是到期，不超过 300000。附行比施放长，所以不受「施放时长 + 冷却」限制 |
| `burn_ticks` | [0, 1200]，目标「灼伤」的时长刻数，已在灼伤时取剩余和它的较大者；0 表示不做。编号 5，原名 `ignite_ticks` |
| `burn_damage` | DAMAGE_POINT，[0, 20]，一次灼伤（`burn_ticks` 刻）一共掉的原版 HP；省略为 0。`burn_ticks` 为 0 时不能填 |
| `chill_ticks` | [0, 1200]，目标「寒侵」每中一次加的时长刻数，封顶由伤害附属定；0 表示不做。编号 6，原名 `freeze_ticks` |
| `status` | `{id, color_rgb}`，附行期间挂在主人身上的状态效果图标；省略不显示。`id` 是 `namespace:path`，颜色在 [0, 0xFFFFFF] |
| `extra_knockback` | RATIO，[0, 10]，原版击退强度；省略为 0 |
| `counters` | 最多 16 条，按目标主属性取倍率，目标属性不重复；未列出的属性 ×1。五行谁克谁由内容附属写在这里，本库不内置 |
| `counters[].damage_multiplier` | RATIO，(0, 10] |
| `counters[].sound_volume_multiplier` | RATIO，(0, 4]；省略为 1 |
| `counters[].sound_pitch_offset` | RATIO，[-1.5, 1.5]；省略为 0 |
| `hit_sound` | 锚点必须是 `TARGET`；省略不播。启动时的未注册声音告警也列它 |

一式最多一个附行效果。附行不生成 `EffectIntent`，宿主不必认识这个算子。

灼伤、寒侵是伤害附属注册的两个状态效果（`meridian_vanilla_damage:burn`、`meridian_vanilla_damage:chill`），
不碰原版的着火刻和冻结刻。旧名 `ignite_ticks`、`freeze_ticks` 的文本目录要跟着改名，否则解析时按未知字段拒绝；
线上编号没变，存档里正生效的附行照样读得出来。

### 附行图标

`status` 声明的图标在模组初始化时按目录注册成不出粒子的有益状态效果（和状态增益同一套），语言和贴图由内容附属提供：
`effect.<namespace>.<path>`、`textures/mob_effect/<path>.png`。几式共用一个 `id` 时颜色要一致。

身体上的附行是准，图标只是它的显示（`TechniqueInfusionStatuses`）：

- **起。** 附行写进身体时，`TechniqueService` 发 `InfusionBegan`（`addInfusionListener`）。表现层摘掉这个实体身上所有附行图标，
  再挂这一个，时长取附行剩余时间折成 tick 向上取整。换一式就是旧图标没、新图标上；起手被打断不写附行，也就没有事件、没有图标。
- **到期。** 图标按挂上时的时长倒计时，和附行同一刻到头；图标自然到期不回头动身体。
- **图标被清掉。** 牛奶、`/effect clear`、不死图腾把图标清掉时，表现层调 `TechniqueService.endInfusion(entityUuid, statusId)`，
  提交一笔只带进度的 `BodyActivity` 清掉 `active_infusion`，请求 ID 是 `technique_end_infusion_<cast_id>`。
  身体上的附行不是这个图标的（已经换了一式）、已到期、死体、宿主拒绝，都不动身体，返回 false。
- **外面给的图标不收。** `/effect give` 之类直接给附行图标，`addEffect`、`forceAddEffect` 入口拒绝。
- **装载。** 玩家读档后、别的实体带着附行图标读档后，等身体宿主认领（最多 100 tick），用 `TechniqueService.activeInfusion` 查一次：
  有附行就按剩余时间重摆图标，没有就摘掉。
- 全部由事件触发，平时不读身体。

不出粒子的状态效果继承 `TechniqueQuietEffect`：`/effect give` 给出来的也不出粒子、照常有图标。
状态增益、附行图标和伤害附属的灼伤、寒侵都是它。

### 身体上的附行

`TechniqueProgress.active_infusion=7` 存 `ActiveInfusion{cast_id, technique_id, started_millis, infusion}`，一身至多一个。
`infusion` 是定义快照：目录之后改了或卸载了，已生效的附行都不变。

- **写入。** 效果时刻和其他效果一样，先按起播点裁剪，再按动作速度缩放。到点时，本库自己提交一笔只带进度的
  `BodyActivity`，请求 ID 是 `technique_infusion_<cast_id>`，整份覆盖身体上原有的附行。
  - `started_millis` 取排定时刻，迟到的 tick 不会把起点推后。
  - 同一推进边界的顺序：先登记曲线，再付分段费用，然后写附行，最后交付同刻世界效果。
  - 身体里已经记着本次 `cast_id` 的附行就不再提交，回执丢失后重新 inspect 也不会重复写。
- **不写。** 推进跨过效果时刻之前施放被打断、取消或卸载，这次附行就不写，身体上原有的附行保留。
  身体时钟已过效果时刻、但还没推进到那一刻就停下时也不补写，和分段费用、世界效果一样。
- **收势**不清附行。
- **到期**按主人身体时钟，在查询时判断：过了曲线末点就当作没有。进度里的记录留到下次被覆盖或清除，不主动删除。
- **死亡。** 死体的时钟不再前进，附行不会自己到期，所以 `recoverOnLoad` 在死体上清掉它：
  - 有残留施放时，随施放一起清；
  - 没有施放时，另交一笔可信进度，请求 ID 是 `technique_recover_infusion_<cast_id>`。
  - 活体卸载恢复保留附行。
- **旧存档**没有这个字段，按没有附行处理，不迁移。

### 附伤数值

`TechniqueInfusions` 的公开方法都是纯函数，不读服务状态，调用方自己检视身体后传进来。

- `strike(owner, Optional<target>, doseHp)` 返回 `Optional<InfusionStrike>`。
  - 主人身体死了、没有附行、曲线值为 0（起点以前、末点以后）或剂量不大于 0，都返回空。
  - 附伤 `damageHp = doseHp × ratio × 曲线值 × 相克倍率`，单位和剂量相同（原版 HP）。曲线在相邻拐点间线性插值。
  - `burnTicks`、`burnDamageHp`、`chillTicks` 原样取目录里的值，不乘曲线和相克倍率。
  - `InfusionStrike` 另带伤害类型 ID、倍率、灼伤的时长和总血量、寒侵加的时长、追加击退，以及按相克调好的命中声。
  - 命中声：音量乘倍率后不超过 4；音调加偏移后夹在 `[0.5 + 抖动, 2 − 抖动]` 里，抖动的两端也能播。
- `primaryAttribute(body)` 求目标主属性：
  - 只看所有经脉的内腔，不算管壁和器官缓存；
  - 按属性把数量加总，`meridian:none` 不参选，但计入总量；
  - 数量最多且唯一的那个属性，占总量不低于 `PRIMARY_SHARE_THRESHOLD`（0.25）才算主属性；
  - 并列、只有无属性、不到门槛，都没有主属性，倍率 ×1；
  - 枯、凋零这类属性也可以是主属性，内容附属不给它们写相克，就按 ×1。
- `remainingMillis(body)`：到曲线末点还剩多少毫秒；死体和没有附行都是 0。
