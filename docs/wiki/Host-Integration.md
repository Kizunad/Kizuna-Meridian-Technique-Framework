# 宿主与施放流程

`TechniqueCasting` 只持有不可变 `TechniqueRegistry`。
身体、时钟、释放账户和持久回执都归实现 `TechniqueHost` 的宿主；本库不保留身体缓存或独立账本。
`server/src/test/java/.../MemoryHost.java` 是内存假宿主，底层调用真实 Framework 的 `handleActivity`。
`TechniqueHostDirectory` 通过 Fabric 的 `meridian_technique_host` entrypoint 找到唯一认领者；
Fabric 装配纯 Java `TechniqueService`，负责主线程 tick、学会命令和效果转发。

内容附属可直接在 `fabric.mod.json` 声明清单，不需要 Java 入口：

```json
"custom": {
  "meridian_technique:catalogs": ["data/example/techniques.txtpb", "data/example/extra.binpb"]
}
```

单个路径也可直接写字符串。清单是 `TechniqueCatalog`，须包含 `schema_version: 1`；
附属须硬依赖 `meridian_technique` 及其实际内容依赖，并在两端安装相同的目录资源。
包含卷轴的内容包使用 `environment: "*"`；所有目录按模组 ID 排序发现，再合并校验一次。

需要代码装配的附属仍声明 `meridian_technique_catalog` entrypoint，可选择以下接口：

- `TechniqueCatalogEntrypoint.registerCatalogs(source, catalogs)`：调用
  `catalogs.register(source, addons, catalog)` 收集原始目录，允许引用其他模组尚未加载的清单。
- 既有 `TechniqueRegistryEntrypoint.registry()`：仍返回不可变注册表。加载器保留其原始目录与模板上下文，
  与所有清单及其他入口重新合并校验；入口数量不再限制为一个。旧接口构建时仍须独立合法，
  如需引用仅存在于其他来源的书卷或式，应改用上面的收集接口。

入口的 `phase()` 返回 `TechniqueCatalogPhase`：新收集接口默认 `MOD_INITIALIZATION`，
两端初始化时求值一次并固定目录，可自动生成卷轴；旧注册表接口默认 `SERVER_STARTED`，
每次服务器启动重新求值，物品注册表此时已冻结，因此不生成卷轴。
旧接口可显式返回 `MOD_INITIALIZATION`，前提是构造器和目录装配都可在两端无世界环境运行；
新接口需要世界状态时也可显式返回 `SERVER_STARTED`。入口实例和阶段声明在初始化期读取，
阶段只控制目录回调时机。不同阶段的贡献者保留原发现位置，最终服务端注册表仍统一校验。

卷轴学习自动走本页的服务端宿主入口，内容附属不需要另写右键代码或注册物品。
完整身份、创造栏、tooltip 和消耗规则见[数据契约](Contracts.md)。内容包可提供
`assets/<namespace>/textures/item/<卷路径>_scroll.png` 和同路径的 `models/item/<卷路径>_scroll.json`；
无专用贴图时会使用功法库的通用卷轴，缺失只警告一次，不影响学习。

客户端可监听 `TechniqueScrollClient.LEARNED`，在服务端成功学习后收到不可变的
`TechniqueScrollCatalog.Scroll`，回调运行在客户端主线程。内置只读阅读界面也监听此回调并自动打开。
通知通道为 `meridian_technique:scroll_learned`，只传卷 ID，没有客户端学习请求或确认回包。
`TechniqueScrollClient.background(scroll)` 返回 `textures/gui/scroll/<卷路径>.png` 的资源 ID；缺图时
阅读界面使用功法库内置通用纸面并对该资源告警一次。背景建议为 1536×1024；正文在不同 GUI 缩放下
保持换行，并裁切在纸面中间视口内。书名·卷名、式语言键名称、`presentation.description` 和前置卷均来自
不可变目录展示数据。`NamedTechnique.definition()` 保留只读定义，数值行直接从费用、阶段与效果生成。
式名按语言条目、`presentation.display_name`、可读占位依次解析，绝不回退为资源 ID。
滚轮浏览正文；ESC、合上按钮、纸面外点击、背包键、右键或使用键均可关闭，键位跟随改绑。
客户端类只能在客户端入口引用；没有接收端时不发阅读通知，服务端学习与物品消耗照常完成。

清单校验需要 Framework 的模板和符号。启动器按 `binding_templates` 的命名空间读取已安装附属导出的
`META-INF/kizuna_meridian/addons/<namespace>.pb`，递归收集其声明依赖，以 `AddonRegistry.load` 校验完整内容包集合。
采用 `meridian:java_reference` 校验画像及参考核心已实现的能力；这是只读目录校验，不替代实体宿主的实际运行能力。
包必须支持该画像，模板精确版本必须存在；缺资源、依赖不兼容、循环或格式错误均中止启动。
同一路径存在多个副本时只允许内容相同，不按类路径顺序覆盖。
Human 已导出的 `human.pb` 可直接使用，功法库不依赖 Human Java 类，也不改写其身体注册表。
没有这类导出资源的宿主可用编程式入口传入自己的 `AddonRegistry`。

测试目录与宿主仅在测试源集和 GameTest 资源中声明，不进入正式产物。
宿主和战斗 UI 在服务器启动后通过唯一公开入口 `TechniqueFabric.service()` 取得当前服务；
服务器尚未启动、已停止或没有目录时抛出 `IllegalStateException`，不返回 `null`。
服务只在服务端主线程使用，网络请求须先切回该线程，客户端不能直接调用。

受击僵直只有一个写入方：原版伤害附属在身体伤害提交、观察者通知之后、死亡处理之前调用：

```java
TechniqueService service = TechniqueFabric.service();
service.stagger(entity.getUUID(), new TechniqueService.Stagger(durationMillis, movementMultiplier));
```

玩家、类人和以后的宿主都经过这一入口，宿主自己不再判定受伤打断。服务为每个宿主记一份僵直到期时刻
（身体时钟毫秒，服务端权威）；多次命中取较晚到期，不累加。时长 0 不记录也不打断。
僵直期间新起式回执 `STAGGERED`（「僵直中」），已在出手或收势的施放照常完成；
起手中且声明 `interruptible_by_damage` 的施放立即打断。到期由 `tick()` 按身体时钟结束。
`staggerRemainingMillis(entity)` 只读剩余时长；`addStaggerListener` 在开始、延长和结束时通知
`StaggerChanged(entity, remainingMillis, movementMultiplier)`，结束通知为 `0 / 1.0`。
Fabric 层据此给实体加临时 `MULTIPLY_TOTAL` 移动修饰，并把 `meridian_technique:stagger`
（`StaggerStatus` protobuf）发给追踪者和本人，客户端 `TechniqueStaggerClient.remainingMillis` 只供界面读取。
卸载、下线清除僵直记录。

施放与查询同样调用 `service.cast(entity.getUUID(), request)`、`service.known(entity.getUUID())`；
目录使用 `service.registry()`，与命令、状态机共享启动时的同一份不可变目录。

| 宿主方法 | 必须履行的行为 |
|---|---|
| `inspect()` | 先按旧动作结算至可信当前身体时刻，再返回 `Inspection(body, heldItems)`；双手事实与身体同时采集 |
| `findActivity(requestId)` | 重新验证授权，查同一身体、`TechniqueHost.CALLER_ID` 下 Framework 持久活动回执 |
| `submit(Submission)` | 检查 `expectedRevision`，走 `handleActivity`，原子保存身体、回执及释放账户；返回实际活动和 revision |
| `submitEffect(EffectSubmission)` | 校验 `EffectIntent`，幂等执行冲量、表现、移动限速、持物打击、劈门或状态增益；本库不直接改世界 |

所有调用必须与该身体的其他事件串行。拒绝抛 `TechniqueErrors.HostRejected`，包含原始 Framework 失败码；
不确定的存储结果由调用方用原 `cast_id` 重试，不允许换 ID 再扣一次。
身份和身体归属必须由宿主认证，不能从客户端请求中的身体 ID 推导权限。
宿主为库活动授权的固定调用身份为 `meridian_technique:caster`；这不是绕过玩家授权的通行证。

起手流程按以下顺序进行：

1. 校验 `CastRequest`。客户端不能上传费用、效果剂量或知识记录。
2. 按 `technique_start_<cast_id>` 查持久回执；相同请求返回原起手结果并标记 `replayed`，不同请求返回冲突。
3. 宿主结算时间并提供身体与双手事实。检查存活、精确功法版本、可信 `known`、冷却及旧曲线结束；
   有其他施放时必须存在合法承接窗口。重新绑定并按书级规则选手，没有可用手时拒绝。
4. 按每项 `QiCost.distribution` 扣除绑定经脉的内腔费用：`EACH` 每条经脉扣 `amount`，`EVEN` 在全部绑定经脉间平摊总 `amount`；释放实际扣除的真元，不能扣外壁、未开拓经脉或其他属性余额。
5. 生成一份 `BodyActivity`，包括 `accounts`、`released_qi`、`efforts`、`start_actions`、`add_modifiers`、`progress`。
   组织和器官占用取并集。经脉不因施放自动接管自然扩散；新增修正和扣费同一 revision 原子生效。
   承接时在同一活动中加入前式 `stop_actions`，原子替换动作，不先独立取消前式，也不提前其冷却。
6. 预检力量、完整做功区间与原子活动，再交给宿主提交。成功提交只增加一次身体 revision。

冷却从正常收势结束算；僵直打断、卸载、下线等提前终止从实际终止身体时刻算。
起手先保存计划冷却结束时间，终止活动再原子改写；迟到的正常收势仍以原收势边界计算。
所有费用起手一次付清，提前终止不退费，熟练度不改变数值。
Framework 负责复核 `旧总量 = 新总量 + 释放`、余额、容量、功率、耐力和动作有效性。
库只构造计划，不负责把默认释放账户再转入某个世界区域。
`EVEN` 的每条经脉余额单独预检，任一不足就整笔拒绝；扣费后的不平衡由 Framework 的 `PassiveQiDiffusion` 自然回流，本库不再分配。

`advance(host)` 根据保存的身体时钟边界返回 `WARMUP`、`EFFECTIVE`、`RECOVERING`。
收势结束再提交一份活动，停止本次动作、清 `active_cast`，保留知识和冷却。
没有逐毫秒循环，也不会因查询阶段状态修改资源或执行世界变化。
需要效果意图时，宿主用 `advance(host, previousMillis)`；返回的 `AdvanceResult.effects` 是区间内到期的世界效果，
`recoveryCommit` 是收势或取消活动的可信终止回执。组织修正不走效果意图，而是在起手活动里随曲线提交。
`advancedMillis` 是该结果实际读取并调度到的身体时刻；成功分发后用它更新游标，不能另行 inspect 后猜测。
失败结果保留原 `previousMillis`，不能据此跳过效果。

`interruptByStagger(host, previousMillis)` 只由服务的僵直入口调用。起手窗口是半开区间 `[start, warmup_end)`，
只有该窗口内且声明可打断时停止。起手结束边界上的僵直不取消施放。
`cancelOnUnload(host, previousMillis)`、`cancelOnDisconnect(host, previousMillis)` 清当前施放与动作，
不退任何费用，并重算冷却。三个提前结束入口都返回 `AdvanceResult`；若 `previousMillis` 已达到表现的
生效时刻，则同时返回对应 `PresentationAction.STOP`，一次性表现同样停止。
限制移速的施放还会返回 `UNLOCK`；重复清理必须幂等。
宿主必须在冻结、撤销访问权或销毁实体之前完成停止提交；失败须处理或重试。
多次查询到空施放返回 `IDLE`。

`TechniqueService.cast(entity, request)` 先解析唯一宿主，再调用现有状态机；无人认领返回 `NO_HOST`，多个认领抛出接线错误。
有会话时沿用其宿主的活动回执与实时快照，避免认领探测快照在推进前式后过期。
新 `cast_id` 起手前先推进并交付前式到期效果和待确认批次；未全部确认时拒绝替换，不扣后式费用。
承接活动把前式清理保存到后式 `predecessor_cleanup`，交付顺序是前式 STOP/UNLOCK，再后式 LOCK/START。
直接调用低层 `TechniqueCasting` 的宿主必须自行保证同样的前式交付、确认及清理顺序。
`known(entity)` 读取可信 `TechniqueProgress`，返回精确功法 ID 和目录名称。`learn(entity, technique)` 生成只含
`progress` 的 `BodyActivity`，使用身体 revision 原子提交；成功后发布 `KNOWN_CHANGED`，重复学会保持幂等。
Fabric 模组在 Framework 的 `/meridian` 注册点登记 `technique learn`，只允许权限 2；ID 可为书、卷或单式，指令写法见 [Framework 指令页](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/wiki/Commands)。
ID 补全取当前服务器目录中的书、卷、式身份，去重排序，支持命名空间过滤。
`learn(entity, volumeId)` 先检查同书前置卷的全部精确式版本已学会，再以一次活动学会整卷，一次发布 `KNOWN_CHANGED`。
`learn(entity, bookId)` 将书内所有卷的精确式版本以一次活动原子学会；同次学习包含其同书前置卷，
提交失败不留下部分知识，成功只增加一次 revision、发布一次通知；重复学习保持幂等。
已有列表仍只存单式，不保存额外卷进度；单式 ID 命令继续选择最高版本，精确引用学习入口也保留。
每 tick 只推进已成功起手的实体，正常推进和收势共用一次 `advance`。
效果请求 ID 固定为 `technique_effect_<cast_id>_<effect_id>`，与事件游标和批次无关。
服务保留整份待确认结果，全部效果收到回执才更新到 `advancedMillis`。收势活动可能已经提交，效果暂时拒绝时
仍重试原批次，不重新读取空施放后丢掉意图；部分已成功的效果由回执去重。重复 `cast` 不重置现有游标。

`REVISION_CONFLICT`、`TIME_REQUIRES_SETTLEMENT`、`PENDING_TIME_EVENT`、`ENVIRONMENT_UNAVAILABLE`、
`ENVIRONMENT_REVISION_CONFLICT` 和 `INTERNAL_ERROR` 保留会话供下一 tick 重试。
其他业务拒绝（包括目标消失返回的 `MODULE_UNAVAILABLE`）、`UNBOUND/BODY_NOT_FOUND` 或损坏状态
使会话转入终止，不再补发尚未成功的业务效果。身体取消和每项 STOP/UNLOCK 各自保存完成状态；
某项清理拒绝不能阻塞其他清理，任何拒绝码都保留到原宿主重新接受并返回成功。
`UNBOUND` 仅表示访问不可用，不能据此认定实体已销毁或释放清理责任。
清理意图在起手时按保存声明生成，包含可能尚未开始的表现及前式清理；宿主必须允许对未开始表现幂等 STOP。
身份、完整载荷及原计划收势时刻固定，取消时收到即执行，不按未来时刻再次排期。
全部外部清理和身体取消都成功后才移除会话。尚在清理的实体不能开始另一施放。

同一实体的交付不允许重入。效果回调中的取消、断线或僵直打断请求先排队，当前批次退出后再处理；
重复迁移合并，卸载与断线优先于伤害。回调内取消返回空结果表示已排队，不能解释为取消失败或没有会话。
再次排队留给下一 tick，避免同步递归；回调内 `tick` 不重入该实体，新施放暂时拒绝。

`ServerEntityEvents.ENTITY_UNLOAD` 对非玩家实体调用 `cancelOnUnload`；玩家断线由
`ServerPlayConnectionEvents.DISCONNECT` 调用 `cancelOnDisconnect`，避免同次下线重复处理。
服务器停止前取消剩余施放；`shutdown()` 在清理仍被拒绝时明确报错并保留会话。
实体宿主必须让这些回调先于撤销身体访问执行，失败后继续提供取消和清理访问，已支付费用不退回。

所有状态保存在 `addon_progress["meridian_technique:state"]` 的 `TechniqueProgress`：

- `known`：精确功法版本、熟练度、可信来源与学会身体时刻；管理命令是当前学会入口。
- `cooldowns`：功法 ID 到冷却结束身体毫秒；跨功法版本共用冷却。
- `curve_ends`：功法 ID 到已提交修正最晚到期毫秒；提前终止也保留完整曲线。
- `active_cast`：原始请求、三段固定边界、动作身份、绑定、打断规则、选手、移动限速、冷却长度及表现声明；至多一份。
  同时保存 `clip_start_millis`、起手书级 `held_item_rules` 和直接前式 `predecessor_cleanup`，可跨重试稳定清理。
- `last_cast`：最近施放的精确式版本、cast ID、原定生效/收势结束边界和终止原因；仅正常结束可用于历史承接窗口。

损坏、类型不符、未来版本或超限进度拒绝，不按空进度覆盖。
快照和 Framework 回执必须一起保存/恢复；只恢复快照会丢失已结束施放的去重能力。
历史重试返回原起手结果，当前实际状态应另调 `advance` 检查。
同一 ID 的方向或目标变化也视为不同请求。
内容卸载后仍能解除已保存动作、停止已开始表现并移除限速；新状态保留清理所需声明。

效果游标、待确认结果及终止中的独立清理只保存在服务器内存，不跨重启恢复，
也不补发离线期间的冲量或表现。身体已收势而清理尚未成功时，进程崩溃可能丢失该内存清理，
本接口不提供跨身体存储和实体副作用的崩溃事务。
正常卸载、下线和停服会先取消当前施放；异常退出遗留的 `active_cast` 必须由宿主在恢复身体时、
重新对外认领之前，调用 `TechniqueService.recoverOnLoad(entityUuid, recoveryHost)` 清理。
`TechniqueRecoveryHost` 不依赖实体目录已认领：`snapshot()` 返回权威事实，`submitEffect` 先处理幂等的 STOP/UNLOCK，
`submitRecovery` 再将库生成的
`BodyCommand` 按类型交给 Framework 的 `handleActivity` 或 `handleAddonProgress`，保留原始请求 ID、
`expected_revision` 和失败码，并返回实际提交版本。调用与身体事件串行，恢复前由宿主结清时间。
存活身体通过活动同时清施放与动作；死体动作已被 Framework 清空，因此只通过可信进度入口清 `active_cast`。
成功后清除内存旧会话；任一清理效果或进度提交拒绝都不当作成功，可用原身份重试。
恢复只清理当前及已保存前式的 STOP/UNLOCK，不补发冲量、打击、劈门或 START；
保留知识、已付费用和曲线，并按恢复终止身体时刻重算冷却，不开放承接窗口。
只有正常 `FINISHED` 使用原定收势边界计算冷却；`CANCELLED/INTERRUPTED` 使用实际终止身体时刻，
即使恢复已晚于原定收势结束，存活身体与死体也遵循同一规则。
服务不枚举存档身体，不恢复旧施放或重建效果游标；损坏进度仍明确拒绝。

Human 宿主需要同步适配以下契约：

1. `TechniqueRecoveryHost` 新增必需方法
   `TechniqueHost.EffectCommit submitEffect(TechniqueHost.EffectSubmission submission)`，属于源码不兼容变更。
   默认 composite build 直接引用相邻 Technique 服务，旧恢复实现缺少此方法会编译失败，
   与 Damage §5 的先前报告一致。不能用空实现返回成功掩盖缺少实体清理。
2. 恢复适配器应在宿主对外认领前、死体清理时也可执行 STOP/UNLOCK，并按原 request ID、cast ID 和 effect ID
   幂等处理；内容冲突拒绝，成功返回原 request ID 的 `EffectCommit`，失败抛 `TechniqueErrors.HostRejected`。
   可由 `HumanTechniqueHost` 创建带实体上下文的恢复适配器，委托身体的 `snapshot/submitRecovery`，
   与正常效果路径共用执行器。恢复效果不能经当前 `exchange` 重新路由：未认领时它返回 `UNBOUND`，
   且 inspect 的死体恢复路径可能再次进入恢复。保留先清理效果、再提交进度、最后认领的顺序。
3. `HumanTechniqueHost` 的 BODY 回执必须提供同次采集的双手物品 ID、标签、显式
   `damageable/contains_items` 和主手侧；空手也需提供明确的空物品消息，否则新协议拒绝 inspect。
4. 正常实体效果路径还需执行移动 LOCK/UNLOCK、支持 BACKWARD，并让表现 START/PLAY_ONCE 使用
   `start_millis`、STOP 清理已启动表现。移动限速按 cast ID 管理并同步客户端，生命周期终止要移除限速，不能阻断冲量。
   开发依赖和 Fabric 本地产物也需同步更新；未执行的意图不能直接确认成功。
5. 新增 `weapon_strike`、`break_blocks` 的 oneof 分支，必须按下列规则执行后再确认；
   并按 cast 和 `dedup_group` 保存命中记录，接入末击耐久、Damage 手臂侧和双手做功、玩家破门权限。
   不能静默接受未执行的分支。

6b 收尾时只读可见 Human 工作区已有 `HumanTechniqueRecovery.submitEffect`，inspect 已调用双手采集，
效果执行器已分派移动限速、BACKWARD 和表现；`weapon_strike/break_blocks` 仍进入 `MODULE_UNAVAILABLE` 拒绝分支。
以上源码核对不等于集成验收，本轮没有修改或构建 Human；完整接线与实体执行由 Human 侧完成。

持物打击目录须显式填写 `dedup_group`，横扫两半圈同组，连门三刀分别一组。
目标 helper 的参数已由单个 `Set<String>` 改为当前 cast 的 `Map<String, Set<String>>`，键为组名；
宿主不得再用一份整 cast 的集合禁止跨组重击，也不能把不同组或不同 cast 的命中相互混用。
全部组的不同目标并集仍受 `cast_target_limit` 限制。去重组不替代稳定效果身份和回执，重试仍须幂等。

Framework 现有接口限制已记录，未修改依赖：

- 活动做功没有力量字段。本库提交前用 `TissueCapabilities.exert` 校验同一 revision 的力量和完整时段，
  提交时复核 revision；Framework 回执只携带功率/秒，不独立保留力量要求。
- Framework 没有全局标签目录或模板枚举；本库使用显式模板引用校验词汇。
- Framework 死亡会清动作并保留附属进度，但拒绝死体 `BodyActivity`。
  宿主优先在死亡前取消；身体已致死或恢复旧死体时，使用上述 `recoverOnLoad` 走可信进度清理。
  此入口不复活身体，也不补回死亡释放的资源。

字节适配的 `TechniqueHostRequest.expected_revision` 显式携带活动提交依据的版本，宿主必须在同一实体事件队列中复核。
`presentation` 通过 `effect` 交由宿主执行，开始时从 `start_millis` 播放；持续型在正常收势停止，
提前终止停止已开始的持续型及一次性表现。缺省倍率 1 的式在首 tick 下发起手效果；
`movement_speed_multiplier<1` 的式在起手活动成功后立即下发 LOCK、倍率及同刻效果，
收势、承接、打断、死亡、卸载、下线或恢复时 UNLOCK。两种效果保留原稳定身份。
宿主按 cast ID 派生属性 UUID，用 `MULTIPLY_TOTAL=(倍率-1)` 管理临时移速属性并同步客户端；
重复操作去重，移除不存在的修正无副作用。倍率必须在 (0,1] 内，缺省为 1，不拦冲量或跳跃。
本库只发意图，真实属性、动画和速度执行仍属宿主。
inspect 字节身体分支必须提供双手 ID/标签/显式耐久与装物状态及主手侧，详见 [数据契约](Contracts.md)。
GameTest 覆盖表现 START/STOP 和冲量下发，正式源码不提供任何表现内容。

持物效果的宿主执行约定：

| 环节 | 宿主责任 |
|---|---|
| 时刻与回执 | `scheduled_millis` 保留原身体时刻；按传入顺序执行到期意图。以 request ID、cast ID、effect ID 和完整载荷持久去重，冲突拒绝；执行结果、回执和命中记录需原子记录，重试不能重复伤害、耐久或破门 |
| 清理时刻 | 提前终止或承接的 STOP/UNLOCK 可能带未来的原计划收势时刻，收到立即执行；该值用于稳定身份载荷，不能再次排期 |
| 固定手、当前物品 | 起手只固定逻辑手位；每击读取该手当前物品，重新按意图中的规则匹配类别、耐久和装物状态。空手或装着东西按落空，不退费；不自动换另一手 |
| 当前朝向 | 每击按当时视角加 `facing_offset` 取目标；不把起手方向锁成打击朝向 |
| 单体/扇形 | 单体先取视线距离内第一个生物，无命中则在 60° 后备锥取最近一个；扇形按距离、角度、朝向筛选，近到远取上限 |
| 敌我与去重 | 排除自己、同队、旁观者、标记盔甲架；生物敌我由宿主决定。按 cast、`dedup_group` 保存命中集合，同组每目标一次、跨组可重击；全部组的不同目标并集不超 `cast_target_limit`。可复用 `TechniqueStrikeTargets.select`，成功执行后原子更新对应组与回执 |
| 伤害 | 当前物品主手攻击属性及按目标计算的伤害附魔，依书级类别取倍率或固定伤害，再乘本击倍率；不算暴击和原版攻击冷却。`injury_override` 优先于类别致伤，剂量交 Damage 做肌肉系数、护甲与做功 |
| 双手 | `can_two_hand` 且另一手空时乘 `two_hand_multiplier`（设计值 1.2），Damage 按两臂合计出力及基础功率比例分摊做功；另一手有物品时只用单臂。物理左右由 inspect 主手侧解释 |
| 破盾 | 仅 `break_shield=true` 时结合当前类别；斧类先破盾并使本击打入，使用 `shield_break_millis`（设计值 5000）；剑类本击仍被挡，随后用 `shield_cooldown_millis`（设计值 2000）冷却；无破盾类别不处理 |
| 末击耐久 | 仅 `last_strike=true` 时结算一次，命中或挥空都算；基数乘当前物品书级耐久倍率。有耐久走原版耐久附魔、无法破坏和损坏，无耐久消耗一叠中的 1 个；创造玩家及生物不扣，只扣选定手 |
| 原版攻击/表现 | 施放时屏蔽原版左键攻击，每击调用挥手，结束重置攻击冷却；动画、另一手覆盖层、物品耗尽及破门声效由宿主处理 |
| 劈门类别与范围 | 只有当前匹配类别 `can_break_doors=true` 才执行；按前方距离、宽度和任一方块标签选取，门上下两半合并为一扇，合计不超过 `maximum_doors` |
| 劈门权限 | 玩家检查 `world.mayInteract` 与服主总开关，出生点保护可阻止；生物检查 `mobGriefing`。两者统一通过 `world.destroyBlock(pos, true, actor)` 破坏并掉落，使用原版声音和粒子 |

Fabric 的 `dev.kizuna.meridian.technique.fabric.world` 提供上述世界操作的共享实现，
由 Human、Threatening 等宿主主动调用；功法服务仍只交付意图，不自行操作世界。
`TechniqueWorldEffects` 接收权限策略、敌我判定和一次出手做功边界，避免两种宿主重复伤害与耐久逻辑。
玩家和生物共用方块破坏入口；出手类别按所选手判断，副手斧可直接劈门。
物品耐久仅在功法末击结算，方块破坏不额外磨损工具。
`TechniqueWorldImpulses` 分别替换水平推进和竖直跳跃分量，同刻 INPUT 与 UP 可按任意顺序组合；
宿主按效果身份去重，已成功效果重试不重新修改速度。

## 声音

声音不经宿主效果接口，Human、Threatening 等宿主不需要改动：

- `CASTER`：`TechniqueService` 分发到期效果时，把 `sound` 意图交给声音监听者（`addSoundListener`），
  不提交给宿主。Fabric 层 `TechniqueSounds` 在服务器启动时注册监听，按实体 UUID 找到施放者，
  在其位置调用 `world.playSeededSound(null, pos, event, SoundSource.PLAYERS, volume, pitch, seed)`。
  同一施放的批次重试按效果身份去重，不会重复播放。
- `TARGET`：随 `weapon_strike` 意图的 `hit_sounds` 下发。`TechniqueWorldEffects` 对 `hurt` 返回 true 的每个目标
  在其位置播放；宿主照常调用共享打击实现即可。
- 跃步起跳：`TechniqueLeaps` 在服务端付费成功、施加初速度后播放 `takeoff_sound`；客户端预测不播，
  施放者本人只听到服务端下发的一次。落地仍是原版方块脚步声。
- 打断、取消、卸载后不再分发后续效果，之后的声音自然不播。
- 声音类别固定为 `PLAYERS`，原版按距离与音量衰减；本库不另设距离上限。

## 数据驱动状态与跃步

初始化目录的 `status_benefit` 在注册表冻结前创建原版有益 `MobEffect`，因此原版 `/effect give`
和补全均可使用内容身份。效果固定 ambient、无粒子、显示内容图标；重复施放只刷新同一状态，不叠加。
宿主调用 `TechniqueStatusBenefits.apply`，不在人体或类人里硬编码具体功法 ID。

`LeapProfile` 指定每次做功、直线预算、升降上限、吸附半径、起跳速度与飞行 tick 范围、落地间隔、动画身份与 AI 门槛。
准星命中点先夹到升降上限，再沿起点方向缩进 `maximum_path` 球内；候选在同一球内扫描，先吸附准星邻域，
再沿视线带搜索脚下一格以外的顶面，都没有时落向投影后的准星并标记回退。
飞行 tick 为 `round(直线长度 / (launch_speed / 20))` 夹到最短与最长之间；离散弧顶低于两端较高者 0.5 格时
每次加 2 tick 直到满足或到上限（`TechniqueLeap.flightTicks`/`apex`），再反解初速度。
玩家按键先补发一次原版位置与视线包，再发送有界 Protobuf `LeapMotion.REQUEST`；服务端按同一起点和准星求解。
客户端使用同一纯求解器预测：START 回执的起点差 ≤0.05 格且初速度差 ≤0.02 格/tick 时只采用权威落点高度，
否则从权威起点重放本地已飞的 tick；飞行期间客户端位置不能覆盖权威轨迹。
服务端每推进一次玩家飞行就同步原版移动包的比较基准，落地后的首个移动包不会按空中旧位置判为 moved wrongly 而触发传送。
落地不发传送包：FINISH 回执带服务端落点与 `ready_tick`，客户端本地落点差 ≤0.25 格时不移动（原版移动包让服务端跟随），
超过才按差值纠正；落地间隔以服务端世界时间为准，客户端预测起跳后到收到 FINISH 前不再预测下一跳，间隔内不发包。
服务端变慢时客户端的世界时间会超前，站在地上只差落地间隔的请求由服务端挂起到 `ready_tick` 再起跳，
挂起期间位置与视线包都不采纳，起点和准星与客户端预测时一致，不回拉也不提前缩短间隔。
位移走原版碰撞，落地声取脚下方块，额外跌落仍调用原版摔伤和 Damage 身体链。

每次跃步通过 `TechniqueService.submitLeap` 重新解析身体宿主、检查组织槽位并提交 Framework 做功活动，
返回实际提交的组织做功或拒绝原因（`NO_HOST`、`ACTIVE_CAST`、`NO_BINDING` 或组织能力拒绝名）；`exertLeap` 只返回是否接受，
不扣真元、不改学会记录、不中断既有施放。命令给予的状态不要求学会功法或开脉，身体组织能力仍需满足。
动画通过 `presentLeap` 进入已有宿主 PA 接线，起跳 START、落地 STOP；客户端落点线框不生成粒子。
原版生物目标驱动同一求解器，AI 最小距离和决策间隔来自目录。
单体视线命中以碰撞盒交点计算距离；没有视线命中时，后备锥及扇区按实体位置计算距离。
纯服务测试验证意图、时序、预算与回执；共享工具及真实宿主 GameTest 验证实际世界变化。
跨伤害和持久化的原子边界必须由具体宿主设计；不能仅把请求 ID 存在本库内存会话里。

## 调试入口

调试用途，不进正式玩法。以下两个登记点只在服务端回调，由调试附属在初始化时登记一次；权限检查留在调试命令层。

- `TechniqueFabric.CAST_OBSERVED`：每次 `cast` 返回前回调 `(entityUuid, outcome)`，
  覆盖成功起手、无宿主、冷却、僵直等全部返回路径，载荷就是返回给施放方的同一份回执。
  它转发服务实例上的 `TechniqueService.addCastListener`，服务随服务器启停重建，事件不随之失效。
- `TechniqueLeaps.addLeapListener`/`removeLeapListener`：服务端每次付费起跳回调一次 `LeapAttempt`，
  带实体、落点（含 `SUPPORTED`/`FALLBACK`）、结果和 `LeapExertion`。成功为 `LEAP` 并带实际提交的各组织做功，
  被拒为 `ORDINARY` 并带拒绝原因；落地间隔内的 `WAIT` 与客户端预测不回调。

监听器抛出的运行时异常被吞掉（跃步另记一条警告），不改变回执、做功或飞行。
