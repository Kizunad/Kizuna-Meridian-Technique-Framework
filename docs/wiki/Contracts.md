# 数据契约与校验

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
`StaggerStatus(entity_id, remaining_millis)` 是服务端发给追踪者和本人的僵直显示回执，剩余 0 表示结束；
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
物品语言键为 `item.<namespace>.<卷路径>_scroll`；tooltip 列出卷内各式及前置卷。
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
每份目录必须通过 `binding_templates` 显式引用已登记模板的精确 ID/版本。
加载时分别汇集经脉、组织、器官的标签；槽位的每个标签都必须在对应种类中出现。
这只限定词汇，不把功法绑定到某一出生模板；实际施放按身体当前构型重新绑定。
类型和真元属性用 `AddonRegistry.findSymbol` 检查身份与种类。

| 数据 | 单位、范围与约束 |
|---|---|
| `Quantity` | 显式 `value` 和 `unit`，有限数；缺失、错单位均拒绝 |
| 真元费用 | `QI`（元），[10⁻¹², 10⁹]；按 `matching` 匹配，保留库存属性与密度；`EACH` 按每条绑定经脉扣 `amount`，`EVEN` 在全部绑定经脉间平摊总 `amount` |
| 组织做功 | 力 `NEWTON`：[0, 10⁹]；功率 `WATT`：(0, 10⁹]；时间 `SECOND`：(0, 86400]；`training=true` 只允许模板中声明 `TissueTraining` 的组织 |
| 器官做功 | 功率与时间同上，不允许力字段，疲劳由 Framework 唯一预算计算 |
| 最低组织力量 | 可省略；提供时必须是 `NEWTON`，[0, 10⁹] |
| 三段时长 | 显式毫秒，各段非负，总和 (0, 86400000]；每段均可达 9 s；打断布尔必须显式提供 |
| 冷却 | 显式毫秒，[0, 86400000]；仅 `FINISHED` 从原定收势结束算，`CANCELLED/INTERRUPTED` 从实际终止时刻算（含晚恢复） |
| 槽位 | 最多 16 个；标签全部匹配且不重复，0–16 个；标签和类型至少提供一种；按模块 ID 字典序选择 |
| 绑定数量 | 显式最少 [0, 16]、最多 [1, 16]，最少不大于最多；最少 0 表示可选 |
| 费用槽位 | 真元只允许不可缺省且 `require_opened=true` 的经脉；做功只允许不可缺省组织或器官；训练做功还须命中可训练组织 |
| 效果时刻 | 显式相对起手毫秒，非负且不超过收势结束；非递减排序，同刻按声明顺序 |
| 组织修正 | 复用 Framework `CurvePoint`；2–16 点，首点偏移 0，之后严格递增，首尾值 0，值 [-1, 1] |
| 修正期限与倍率 | 曲线不超过 300000 ms；四项峰值 `RATIO`：(0, 10]；各点倍率不低于 0.05 |
| 冲量 | 前、后、上、朝目标、`INPUT`；速度 `METERS_PER_SECOND`：(0, 100] |
| 表现 | 命名空间资源 ID；一次性或持续到收势结束；剪辑起播 `start_millis`：[0, 86400000]，缺省 0 |
| 移动限速 | `TechniqueDefinition.movement_speed_multiplier=14` 为可选 double，[0,1]，缺省 1；起手至收势有效，0 表示站定且不缩放冲量 |
| AI 提示 | 距离 `METER`：[0, 1024]，最小不大于最大；HP/真元阈值、战斗情境权重为 `RATIO`：[0, 1] 或 [0, 100]；腿力门槛为 `NEWTON` |
| 熟练度 | `RATIO`：[0, 1]，仅保存供未来 AI 使用，不改变费用、时长或效果 |
| 目录规模 | 最多 1024 个精确功法版本；单目录最多 1024 个模板引用；每功法真元/做功各最多 32 项、效果最多 32 项 |
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
全部组共享 `cast_target_limit`。`LegRules` 书级规则固定声明忽略手持物品、要求着地，以及基础伤害、每点靴子护甲加成和每式耐久消耗。
腿法书不能同时声明 `held_item_rules`。

`TechniqueHostFacts` 与身体、双手在同一次 `inspect` 回执中提供 `grounded/in_water/climbing/riding/gliding`，
并可成对提供 `boot_armor/boot_durability`。腿法起手在水中、攀爬、骑乘、滑翔或未着地时返回
`NOT_GROUNDED` /「要站在地上才能出腿」。`EffectIntent.leg_strike=20` 携带起手保存的 `LegRules`，不携带手位规则。

`TechniqueEffect.sound=8`（`SoundCue`）在 `at_millis` 时刻播放一个声音事件；五脏诀设计稿 plans/18 §9.2、§11
里叫 `sound` 的效果就是它。

| 声音字段 | 单位、范围与约束 |
|---|---|
| `sound_id` | 必填，命名空间资源 ID 语法 `[a-z0-9_.-]+:[a-z0-9_./-]+`；不要求服务端注册表里存在 |
| `volume` | `RATIO`，(0, 4]；省略为 1 |
| `pitch` | `RATIO`，[0.5, 2]；省略为 1 |
| `pitch_jitter` | 可省略，`RATIO`，[0, 0.5]；每次播放在 `pitch ± pitch_jitter` 内均匀取值，两端仍须落在 [0.5, 2] |
| `anchor` | 必填，`CASTER`（施放者位置）或 `TARGET`（本次打击真正造成伤害的每个目标位置） |

`TARGET` 必须跟在一条同一 `at_millis` 的 `weapon_strike` 或 `leg_strike` 之后，中间只能隔其他同刻声音。
调度时它并入该击意图的 `WeaponStrikeIntent.hit_sounds=3`，不单独成为意图；挥空、被盾挡下或目标免疫时不响。
挥空也要有声音时，在目录里另写一条同刻的 `CASTER` 声音；代码里没有「挥空换声」的特殊分支。
`LeapProfile.takeoff_sound=18` 是跃步起跳声，锚点只能是 `CASTER`，每次实际起跳播放一次。
`EffectIntent.sound=16` 只承载 `CASTER` 声音，效果身份仍是 `effect_<index>`。

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
没有合法窗口且另有当前施放时仍返回 `ALREADY_CASTING`。学会、资源、身体、冷却和旧曲线限制始终检查。
承接提前结束前式收势，保留其原定冷却与曲线；后式起手、生效、收势边界均按起播偏移前移。
原效果时刻小于起播偏移的预备效果不发，包含组织修正；其余效果减去偏移，剩余修正仍在起手完整原子提交。
跨过起播点的持续表现是例外：在新起手发 START，剪辑起播为原起播值加已跳过时长；一次性预备表现不补发。

`QiCost.matching` 缺省为 `EXACT_VARIANT`，保留旧目录的精确属性、精确相态支付规则。
`ANY_ATTRIBUTE_AT_LEAST_PHASE` 必须将 `attribute` 留空，`phase` 为最低可用相态；
例如最低 `FREE` 允许气态库存支付，最低 `GAS` 不接受游离态库存。所有模式都必须声明有效相态，未知匹配模式拒绝。
任意属性支付按相态由低到高、同相态按属性 ID 排序取足费用，可以合计同一经脉内的多种库存。
每一项扣除和释放都保留原始属性与实际密度，不发生相态转换，也不按密度放大或缩小元数。
取用复用 Framework 最小流转单位与可见减法限制；不能足额支付就拒绝整次起手，不提交部分费用。

`QiCost.distribution` 必须显式填写。`EACH` 适合用标签和数量把槽位收窄到具体经脉；`EVEN` 适合用宽标签和较大 `maximum_count` 选中全部候选经脉。
`EVEN` 使用 Framework 的 `double` 真元数量和最小可流转单位：经脉 ID 按字典序排列，除最后一条外使用相同份额，最后一条承担浮点余数，因此总扣费等于声明的 `amount`。
若末份仅因浮点尾差低于最小单位，则取最小单位后仍须使顺序合计严格等于 `amount`，否则拒绝。
目录对允许的每个绑定数量和起手扣费共用同一分配规则，Framework 的最小单位校验保持不变。
每条经脉都先单独预检余额，任一经脉不足就拒绝整次施放，不在经脉之间挪补。扣费后的不平衡由 Framework 的 `PassiveQiDiffusion` 自然扩散，本库不再次分配。

同一功法的**每条**修正必须满足：
`effect.at_millis + curve.last.offset_millis <= warmup_millis + active_millis + recovery_millis + cooldown_millis`。
生效延迟也计入期限；等于正常冷却结束合法。提前终止可能使冷却早于曲线结束，
进度 `curve_ends` 保留已提交曲线最晚到期时间；同式跨版本要等冷却和曲线都到期才可重施。
本库功法目录采用首尾归零约束。
Framework 接受常值曲线的能力不等于本目录必须接受任意修正数据。
不同功法的曲线可指向同一组织，实际乘积和总上限归 Framework。施放起手把每个绑定组织的
`CapabilityModifier` 与费用、做功、动作和进度放进同一份 `BodyActivity`；修正来源为
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
`EffectIntent` 声明冲量、表现、移动限速、`weapon_strike=13`、`break_blocks=14` 或 `leg_strike=20`，交给宿主执行；不包含身体修正。
每条意图带起手固定的 `selected_hand`，无持物规则为未指定；`effect_id` 在施放内独立且稳定。
打击和劈门意图必须有选定手和书级规则。`scheduled_millis` 为非负的权威身体时刻，迟到 tick 不修改原生效时刻。
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
保留 LOCK/UNLOCK 枚举值与 `movement_lock/movement_unlock` 效果身份，LOCK 现在表示应用正数限速。
宿主按施放 ID 派生 UUID，以 `MULTIPLY_TOTAL=(倍率-1)` 施加临时属性，冲量不乘此倍率。

阶段 4 的 `TechniqueSelector` 先检查成功绑定、可支付、冷却和距离，再按类别权重、情境函数和熟练度
计算权重。候选按功法精确版本稳定排序后使用调用方提供的 `Random` 抽样；零权重或没有候选返回空。
熟练度只影响这一权重，不改变费用、曲线和施放时长。
选择器的外部可用性数据应把 `cooldowns` 与 `curve_ends` 的最大值作为同式可再次施放时刻，
并在 `affordable` 中反映持物等宿主预检；施放入口仍会再次校验。
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
- `TechniqueHeldItems.find(rules, items)` 与 `select` 同一规则选手；没有可用手时为空，不抛拒绝。
