# 数据契约与校验

协议包为 `kizuna.meridian.technique.v1`，Java 生成类为 `TechniqueMessages`。
`TechniqueCatalog`、`TechniqueProgress` 的 `schema_version` 为 1。
字段删除必须保留编号，未知字段、枚举和算子拒绝，不做静默降级。

`TechniqueRegistry.load(AddonRegistry, List<TechniqueCatalog>)` 全部成功后才返回不可变目录。
`TechniqueProtocol.decodeCatalog` 负责有界解析，随后仍必须调用 `load` 完成语义及外部引用校验。
内容可在自己的工程里将 textproto 编译为二进制，运行时传入目录；本库没有内容扫描或热加载机制。

Framework 的标签是开放字符串，没有标签注册或模板枚举 API。
每份目录必须通过 `binding_templates` 显式引用已登记模板的精确 ID/版本。
加载时分别汇集经脉、组织、器官的标签；槽位的每个标签都必须在对应种类中出现。
这只限定词汇，不把功法绑定到某一出生模板；实际施放按身体当前构型重新绑定。
类型和真元属性用 `AddonRegistry.findSymbol` 检查身份与种类。

| 数据 | 单位、范围与约束 |
|---|---|
| `Quantity` | 显式 `value` 和 `unit`，有限数；缺失、错单位均拒绝 |
| 真元费用 | `QI`（元），[10⁻¹², 10⁹]；匹配属性与相态，保留库存密度；`EACH` 按每条绑定经脉扣 `amount`，`EVEN` 在全部绑定经脉间平摊总 `amount` |
| 组织做功 | 力 `NEWTON`：[0, 10⁹]；功率 `WATT`：(0, 10⁹]；时间 `SECOND`：(0, 86400] |
| 器官做功 | 功率与时间同上，不允许力字段，疲劳由 Framework 唯一预算计算 |
| 最低组织力量 | 可省略；提供时必须是 `NEWTON`，[0, 10⁹] |
| 三段时长 | 显式毫秒，各段非负，总和 (0, 86400000]；打断布尔必须显式提供 |
| 冷却 | 显式毫秒，[0, 86400000]，从起手开始 |
| 槽位 | 最多 16 个；标签全部匹配且不重复，0–16 个；标签和类型至少提供一种；按模块 ID 字典序选择 |
| 绑定数量 | 显式最少 [0, 16]、最多 [1, 16]，最少不大于最多；最少 0 表示可选 |
| 费用槽位 | 真元只允许不可缺省且 `require_opened=true` 的经脉；做功只允许不可缺省组织或器官 |
| 效果时刻 | 显式相对起手毫秒，非负且不超过收势结束；非递减排序，同刻按声明顺序 |
| 组织修正 | 复用 Framework `CurvePoint`；2–16 点，首点偏移 0，之后严格递增，首尾值 0，值 [-1, 1] |
| 修正期限与倍率 | 曲线不超过 300000 ms；四项峰值 `RATIO`：(0, 10]；各点倍率不低于 0.05 |
| 冲量 | 前、上、朝目标、`INPUT`；速度 `METERS_PER_SECOND`：(0, 100] |
| 表现 | 命名空间资源 ID；一次性或持续到收势结束；可完全省略 |
| AI 提示 | 距离 `METER`：[0, 1024]，最小不大于最大；HP/真元阈值 `RATIO`：[0, 1]，权重 [0, 100] |
| 熟练度 | `RATIO`：[0, 1]，仅保存供未来 AI 使用，不改变费用、时长或效果 |
| 目录规模 | 最多 1024 个精确功法版本；单目录最多 1024 个模板引用；每功法真元/做功各最多 32 项、效果最多 32 项 |
| 传输与存档 | 载荷最多 1 MiB，解码嵌套最多 32 层；进度含 Any 包装最多 64 KiB |

`QiCost.distribution` 必须显式填写。`EACH` 适合用标签和数量把槽位收窄到具体经脉；`EVEN` 适合用宽标签和较大 `maximum_count` 选中全部候选经脉。
`EVEN` 使用 Framework 的 `double` 真元数量和最小可流转单位：经脉 ID 按字典序排列，除最后一条外使用相同份额，最后一条承担浮点余数，因此总扣费等于声明的 `amount`。
若末份仅因浮点尾差低于最小单位，则取最小单位后仍须使顺序合计严格等于 `amount`，否则拒绝。
目录对允许的每个绑定数量和起手扣费共用同一分配规则，Framework 的最小单位校验保持不变。
每条经脉都先单独预检余额，任一经脉不足就拒绝整次施放，不在经脉之间挪补。扣费后的不平衡由 Framework 的 `PassiveQiDiffusion` 自然扩散，本库不再次分配。

同一功法的**每条**修正必须满足：`effect.at_millis + curve.last.offset_millis <= cooldown_millis`。
生效延迟也计入期限；等于冷却结束合法。本库功法目录采用新版方案的首尾归零约束。
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
缺省和全零均表示使用施放者朝向；非零值仅表达方向，不决定速度或其他数值。
当前保留原值以供幂等比较；`TechniqueScheduler` 对零输入的 `INPUT` 冲量改成 `FORWARD`，非零方向原样交给效果宿主。

`TechniqueHostRequest` 使用标准小写 `entity_uuid` 和非空 `request_id`。
`expected_revision` 只用于 `activity`，由宿主按 Framework 当前 revision 做原子校验；查询和效果操作必须为 0。
oneof 编号固定为 `inspect=10`、`activity=11(BodyActivity)`、`effect=12(EffectIntent)`。
`inspect=false`、缺操作、错误 UUID、非法嵌套活动或效果均拒绝。
`EffectIntent` 只声明冲量或表现的播放/开始/停止，交给宿主执行；不包含身体修正。
`TechniqueHostResponse` 以原 `request_id` 回显未认领、身体快照、活动 revision、效果接受或 Framework 失败。
`TechniqueHostDirectory` 通过 `meridian_technique_host` 字节入口寻找唯一认领者；无人认领由 `TechniqueService.cast` 返回 `NO_HOST`，多个认领停止并报告接线错误。

阶段 3 的 `TechniqueScheduler` 是无状态纯函数：调用方给出上一次已分发的身体时刻和当前已结算时刻，
返回区间内的冲量/表现 `EffectIntent`。`TechniqueCasting.advance(host, previousMillis)` 在收势边界
额外提交停止动作的 `BodyActivity`，并在 `AdvanceResult.recoveryCommit` 返回终止回执。
伤害打断、卸载和下线也使用这个带游标的结果入口；只有此前已分发 `START` 的持续表现才会返回 `STOP`。
目录已卸载时仍能清除动作，但没有定义可用于生成表现停止意图。
`INPUT` 冲量保留起手请求中的水平方向，缺省或全零仍交由宿主按施放者朝向解释。

阶段 4 的 `TechniqueSelector` 先检查成功绑定、可支付、冷却和距离，再按类别权重、情境函数和熟练度
计算权重。候选按功法精确版本稳定排序后使用调用方提供的 `Random` 抽样；零权重或没有候选返回空。
熟练度只影响这一权重，不改变费用、曲线和施放时长。
