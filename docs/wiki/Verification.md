# 规则、测试与交付记录

`TechniqueObserverTest` 覆盖调试入口：施放监听收到每条返回路径的同一份回执、观察者异常不改变回执，
`submitLeap` 接受时返回实际组织做功，无宿主与施放进行中返回对应原因且不扣做功。
`TechniqueSoundTest` 覆盖声音契约：省略字段按默认值播放；越界、单位错、缺锚点、非法 ID、抖动越出 [0.5, 2] 都拒绝；
同刻打击后的目标声音并入 `hit_sounds`，施放者声音只交给监听者，宿主收不到声音意图；目标声音前没有同刻打击时拒绝；
宿主暂拒后整批重试，同一声音只响一次；`Presentation.sound_id` 非空拒绝；未注册 ID 去重后按字典序列出。
`TechniqueLeapTest` 另验证起跳声只能锚定施放者。世界里的实际播放由基础功法完整装配 GameTest 验证：
叩门逐击的命中声与挥空声、破门僵直期间不出声、轻身起跳声、未注册 ID 内联下发。

状态增益与跃步的纯几何回归由基础功法 `LeapTargetTest` 使用正式目录覆盖；`TechniqueLeapTest` 覆盖
平地 3/6/7 格、上 3 格、下 4 格的飞行 tick 与弧顶，直线预算投影、弧顶保护加时、tick 上下限和停用字段校验。完整装配
`LightBodyGameTest` 验证命令注册、无粒子图标、三格墙头、三格台阶飞行 ≤14 tick、连跳做功、落地间隔内请求挂起到时刻再起跳、
疲劳回退、碰墙、额外跌落和卫道士追击。
宿主执行状态意图仍使用原有幂等效果回执，玩家和类人共享同一 Protobuf 配置与求解器。

真实客户端验收执行 `ALSOFT_DRIVERS=null xvfb-run -a ./gradlew -PscrollFullAssembly :fabric:runLeapClientTest`。
该任务复制 GameTest 世界到独立目录，按住真实跳跃键连跳三次，验证实际落点标记、无粒子效果图标、
逐 tick 本地位置无 >0.05 格回拉、全程无传送包、每跳水平 6 格且首跳落点误差 ≤0.5 格，
结果写入 `fabric/build/leap-client-results.txt`，逐 tick 轨迹写入 `fabric/build/leap-client-trace.txt`，
截图为 `fabric/build/leapclienttest/screenshots/light-body-marker.png`。正常发行包不含测试入口。

实现依据为 2026-10-24 的功法方案、2026-10-26 的框架更新，以及
`technique-combat-plans-2026-10-26.md` 中新版 `plans/06-Technique-Framework.md`。
当前交付阶段 1–5、6a、6b、目录清单发现、卷轴物品与客户端阅读界面；Fabric 模组负责双端物品与展示及服务端装配，
身体和世界效果仍由独立宿主入口提供。
阶段 5 依赖核对时 Framework 为 `216710d03ff9bb0e4ae13894ab9b0a8cf7be9a45`；
阶段 6a 验收时为 `e2859475afe6ef4d948f35052e1e112c051ab737`，依赖工作区干净，未修改其受版本控制文件。

测试全部属于公开契约、关键业务流程或模块交互的必要整体回归。
测试功法和双侧/触须身体仅在测试源码集与 `fabric/src/gametest/`，
内存假宿主调用真实 Framework 处理器和存储。

| 规则 | 测试类与测试名 |
|---|---|
| 清单文本/二进制、未知字段、格式版本、大小限制、重复身份、前置引用与来源 | `TechniqueCatalogManifestTest` |
| 导出 AddonBundle 的依赖收集、缺资源、命名空间和不同副本冲突 | `TechniqueAddonResourcesTest` |
| 单字符串/数组、错误类型、模组排序、多个旧入口、代码到清单的跨来源引用与重复冲突 | `TechniqueCatalogDiscoveryTest` |
| 实际启动发现测试模组清单、与旧入口合并并学会整卷 | `TechniqueServiceGameTest.discoversManifestAlongsideEntrypointAndLearnsItsVolume` |
| 物品顺序与按书聚合的创造栏顺序、书名·卷名、式名称和说明、前置卷显示名、重复卷轴来源 | `TechniqueScrollCatalogTest` |
| 多档 GUI 缩放、竖屏与超宽屏的正文边界，标题留白与完整行，缺图回退、背景尺寸、告警去重及补图恢复 | `TechniqueScrollLayoutTest`、`TechniqueScrollBackgroundsTest` |
| 两端模组枚举顺序变化仍生成相同卷轴、初始化目录只读取一次、晚到目录不生成物品 | `TechniqueCatalogDiscoveryTest.earlyCatalogsFreezeOnceAndProduceMatchingScrollsAcrossModOrders` |
| 生存整卷学习消耗一次，重复学习拒绝且不写身体 | `TechniqueScrollGameTest.survivalLearnsAndConsumesOnceThenRejectsDuplicate` |
| 缺前置卷拒绝且不消耗、不写身体 | `TechniqueScrollGameTest.missingPrerequisiteRejectsWithoutConsumingOrWriting` |
| 创造模式按前置学会两卷且不消耗 | `TechniqueScrollGameTest.creativeLearnsBothVolumesWithoutConsuming` |
| 无身体玩家拒绝且不消耗 | `TechniqueScrollGameTest.unboundPlayerRejectsWithoutConsuming` |
| 实际注册表、创造展示与搜索列表、首卷图标、晚到目录仍可服务端查询 | `TechniqueScrollGameTest.creativeGroupContainsEveryEarlyVolumeInBookOrder` |
| 缺数值、缺单位拒绝 | `TechniqueRegistryTest.rejectsMissingUnits` |
| 未指定真元分配方式拒绝 | `TechniqueRegistryTest.rejectsUnspecifiedQiDistribution` |
| 非有限、负费用、过小/过大费用、冲量速度和时间越界拒绝 | `TechniqueRegistryTest.rejectsOutOfRangeParameters` |
| 缺少算子、未知二进制算子拒绝 | `TechniqueRegistryTest.rejectsUnknownOperatorsIncludingWireFields` |
| 未知标签、用错模块种类的标签拒绝 | `TechniqueRegistryTest.rejectsUnknownAndWrongModuleTags` |
| 每条修正结束不晚于冷却，包括生效延迟与第二条曲线 | `TechniqueRegistryTest.rejectsCurvesEndingAfterCooldownIncludingDelayAndEveryModifier` |
| 完整曲线、首尾归零、偏移严格递增；没有提前移除或旧算子 | `TechniqueRegistryTest.requiresCompleteCurveAndNeverAllowsRemovalInstructions` |
| 模板引用、符号种类、已开拓扣费声明与不可变目录 | `TechniqueRegistryTest.validatesReferencesOpenedCostsAndImmutableCatalog` |
| 不同身体标签绑定、稳定选择、返回 revision | `TechniqueBinderTest.bindsDifferentBodiesDeterministicallyWithRevision` |
| 未开拓、组织失能、境界和力量不足有具体原因 | `TechniqueBinderTest.bindingReportsUnopenedUnavailableAndRealm` |
| revision 改变后重绑；可选、仅类型器官槽位 | `TechniqueBinderTest.rebindsChangedRevisionAndSupportsOptionalSlots` |
| 起手一笔活动支付真元、组织做功、器官疲劳、动作和进度 | `TechniqueCastingTest.startSubmitsOneAtomicActivityWithAllCostsAndProgress` |
| `EACH` 每条经脉扣费 | `TechniqueCastingTest.eachDistributionChargesEveryBoundMeridian` |
| `EVEN` 平摊、稳定余数与总量守恒 | `TechniqueCastingTest.evenDistributionUsesStableRemainderAndPreservesTotal` |
| 四条最小单位、相邻总额及所有允许绑定数量 | `TechniqueQiCostTest` |
| 大写、大小写不同、最长施放 ID 的动作与修正合法且不碰撞 | `TechniqueCastingTest.castIdsProduceLegalDistinctFrameworkIds` |
| 旧格式修正与新编码施放共存 | `TechniqueCastingTest.encodedCastIdsCoexistWithLegacyModifiers` |
| `EVEN` 任一经脉不足整笔拒绝 | `TechniqueCastingTest.evenDistributionRejectsWhenOneBoundMeridianCannotPay` |
| 真元不足整笔拒绝 | `TechniqueCastingTest.insufficientQiRejectsWholeActivity` |
| 后续做功预算不足不留下先前资源变化 | `TechniqueCastingTest.insufficientWorkBudgetRollsBackQiActionsAndProgress` |
| 只能从已开拓经脉扣费 | `TechniqueCastingTest.onlyOpenedMeridiansCanFundCasting` |
| `cast_id` 在重启、收势后重试不重复扣费 | `TechniqueCastingTest.duplicateCastIdAfterRestartAndCompletionDoesNotCharge` |
| 同 ID 不同目标或方向拒绝 | `TechniqueCastingTest.sameCastIdWithDifferentTargetOrDirectionIsRejected` |
| 多账户混相态总量守恒、密度保留、外壁不变 | `TechniqueCastingTest.qiConservedAcrossAllAccountsAndHostRelease` |
| 冷却、known、active_cast 随快照往返；身体时钟到期边界 | `TechniqueCastingTest.cooldownAndSnapshotRoundTripUseExactBodyTimeBoundary` |
| 起手打断不退费，冷却保留 | `TechniqueCastingTest.warmupDamageInterruptsWithoutRefundAndRetainsCooldown` |
| 不可打断与起手结束边界，三段状态推进 | `TechniqueCastingTest.nonInterruptibleAndPostWarmupDamageContinueThroughPhases` |
| 零起手、零生效段直接收势 | `TechniqueCastingTest.zeroWarmupAndActiveDurationsEnterRecoveryImmediately` |
| 同一身体第二个功法被拒 | `TechniqueCastingTest.onlyOneCastIsAllowedEvenForDifferentTechniques` |
| 卸载、下线取消不退费，保留知识和冷却 | `TechniqueCastingTest.unloadAndDisconnectCancelWithoutRefundAndKeepCooldown` |
| 未知、未学、死亡拒绝，知识跨死亡保留 | `TechniqueCastingTest.unknownUnlearnedAndDeadBodiesAreRejectedWithChineseReasons` |
| 力量超载在提交前拒绝 | `TechniqueCastingTest.excessiveForceIsRejectedBeforeActivitySubmission` |
| 停止失败保留原施放，允许重试 | `TechniqueCastingTest.rejectedStopRetainsActiveCastForRetry` |
| 死亡后活动清理限制明确报告，知识冷却保留 | `TechniqueCastingTest.deathPreservesKnowledgeAndCooldownButReportsActivityCleanupLimit` |
| 宿主拒绝和过期 revision 不留下部分变化 | `TechniqueCastingTest.hostRejectionAndStaleRevisionLeaveWholeActivityUnchanged` |
| 熟练度不改变费用；修正随曲线到期且不提前移除 | `TechniqueCastingTest.proficiencyDoesNotChangeCostsAndStageThreeModifiersRunToCompletion` |
| 冲量只在声明时刻生成意图并保留请求方向，收势提交返回回执 | `TechniqueStage34Test.impulseIsReturnedAtItsDeclaredBodyTime`, `TechniqueStage34Test.advanceReturnsDueEffectsAndRecoveryCommit` |
| 起手打断不撤销已提交修正 | `TechniqueStage34Test.interruptedCastKeepsSubmittedModifierUntilCurveEnd` |
| AI 同种子稳定选择、过滤不可用和零权重功法 | `TechniqueStage34Test.selectorUsesStableSeedAndIgnoresZeroWeight`, `TechniqueStage34Test.selectorRejectsUnaffordableAndDistantCandidates` |
| 提前结束停止已启动的持续表现，不停止尚未启动的表现；一次性表现见 6a 验收 | `TechniqueStage34Test.earlyTerminationStopsStartedContinuousPresentation`, `TechniqueStage34Test.earlyTerminationDoesNotStopPresentationBeforeItsStart` |
| 熟练度为零仍可选，高熟练度权重更大 | `TechniqueStage34Test.selectorAllowsZeroProficiencyAndFavorsHigherProficiency` |
| 玩家文本不含数值或时间单位 | `TechniqueCastingTest.playerFacingReasonsContainNoTimingOrNumericDetails` |
| 宿主 oneof 编号、三操作往返、表现交给 effect | `TechniqueProtocolTest.hostOperationsUseStableFieldNumbersAndRoundTrip` |
| 缺操作、错误 UUID、截断/超大消息和提前移除拒绝 | `TechniqueProtocolTest.hostRejectsMissingOperationInvalidUuidAndMalformedPayload` |
| 方向缺省/零/有限值接受，NaN 与无穷拒绝 | `TechniqueProtocolTest.horizontalDirectionAcceptsAbsentZeroAndFiniteValuesButRejectsNonfinite` |
| 损坏或超限进度拒绝且不覆盖身体 | `TechniqueProtocolTest.corruptStateAndOversizeAnyAreRejectedWithoutOverwritingBody` |
| 生成描述集包含框架协议，无正式功法资源 | `TechniqueProtocolTest.generatedDescriptorContainsImportedProtocolAndNoFormalContent` |
| 无宿主、宿主多重认领和 request_id 串包拒绝 | `TechniqueStage5Test.directoryRejectsUnboundAndMultipleClaims`, `TechniqueStage5Test.directoryChecksRequestIdAndRoutesEffectOnly` |
| 学会原子写入、`known` 查询、重复学会幂等和 `KNOWN_CHANGED` 通知 | `TechniqueStage5Test.learnPublishesKnownChangedAndKnownReturnsCommittedEntry` |
| 直接服务调用也拒绝目录外功法定义 | `TechniqueStage5Test.learnRejectsDefinitionOutsideCurrentRegistry` |
| `INPUT` 缺省方向转前方、tick 下发效果和收势 | `TechniqueStage5Test.castTickDispatchesForwardImpulseThenFinishes` |
| 非零输入原样传递、显式零向量转前方 | `TechniqueStage5Test.servicePreservesInputDirectionAndTreatsExplicitZeroAsForward` |
| 无宿主施放返回 `NO_HOST` 和中文原因 | `TechniqueStage5Test.castWithoutHostReturnsNoHost` |
| 服务级僵直打断和卸载取消 | `TechniqueStage5Test.serviceStaggerInterruptClearsActiveCastWithoutRefund`, `TechniqueStage5Test.serviceUnloadClearsActiveCastWithoutRefund` |
| 宿主回执索引重建后的活动重放 | `TechniqueStage5Test.activeCastReplaysWhenDirectoryReceiptIsRebuilt` |
| 未认领或身体暂不可访问时保留终止责任，恢复后清理 | `TechniqueStage5Test.lostHostRetainsTerminationUntilHostAcceptsAgain` |
| 效果或收势提交时失去认领仍保留独立清理 | `TechniqueStage5Test.lostHostDuringEffectOrRecoveryRetainsCleanup` |
| 暂时拒绝重试业务，永久拒绝转入终止并重试清理 | `TechniqueStage5Test.temporaryRejectionRetriesAndPermanentRejectionRetainsTermination` |
| 相邻 inspect 间时钟前进不重复效果 | `TechniqueStage5Test.advancingHostClockDoesNotDispatchEffectTwice` |
| 部分效果成功及收势后失败仍重试原批次 | `TechniqueStage5Test.effectRetryPreservesBatchAfterPartialAcceptanceAndRecovery` |
| 同一请求重放不倒退效果游标 | `TechniqueStage5Test.castReplayDoesNotRewindEffectCursor` |
| 服务入口拒绝未知和未学功法 | `TechniqueStage5Test.serviceRejectsUnknownAndUnlearnedClientReferences` |
| 僵直打断可打断起手 | `TechniqueStaggerTest.staggerInterruptsInterruptibleWarmup` |
| 零时长（低于门槛）不打断 | `TechniqueStaggerTest.hitWithoutStaggerDoesNotInterrupt` |
| 出手、收势中僵直不打断，照常收势 | `TechniqueStaggerTest.staggerOutsideWarmupLetsCastFinish` |
| 僵直中新起式回执 `STAGGERED`「僵直中」且不改身体，到期后可起手 | `TechniqueStaggerTest.staggerRejectsNewCastUntilExpiry` |
| 多次命中取较晚到期、不累加 | `TechniqueStaggerTest.laterExpiryWinsWithoutAddingDurations` |
| tick 按身体时钟结束僵直并通知 `0 / 1.0` | `TechniqueStaggerTest.tickEndsExpiredStaggerAndRestoresMovement` |
| 僵直时长 [0, 60000]、倍率 (0, 1] | `TechniqueStaggerTest.staggerRejectsDurationAndSpeedupOutsideContract` |

Fabric GameTest 全部使用真实服务器启动的 `TechniqueFabric.service()`，目录由清单和 entrypoint 合并，
测试宿主由 entrypoint 发现；卷轴测试走服务端玩家的 `gameMode.useItem`：

| 验收 | `TechniqueServiceGameTest` 测试名 |
|---|---|
| 学会命令写进度、`KNOWN_CHANGED`、`known`、真实 tick 下发冲量与表现 START/STOP、收势 | `learnCommandPublishesKnownChangedAndTicksToFinish` |
| 从公开服务入口写入僵直并打断起手，取消不退费且保留冷却 | `staggerInterruptCancels` |
| 测试世界生成实体、施放、`discard` 触发 `ENTITY_UNLOAD` 后清动作和会话 | `discardTriggersEntityUnloadAndClearsSession` |
| 学会命令拒绝目录外 ID 与权限不足，知识不变 | `learnCommandRejectsUnknownIdAndInsufficientPermission` |

补充方案逐项落实：

| 补充要求 | 当前实现 |
|---|---|
| `TechniqueHostRequest` 与校验 | `inspect=10`、`activity=11`、`effect=12`，有界编解码、`expected_revision` 和响应回执校验；目录通过 entrypoint 路由 |
| `direction` 与 `INPUT` | 请求保存有限水平 x/z；缺省或零向量转成 `FORWARD`；非零方向原样交给效果宿主 |
| 玩家失败文本 | `CastOutcome.failure` 保留结构化原因码，`reason` 只描述身体感受；Framework 拒绝额外保留 `framework_failure` |
| 库不持有身体 | `TechniqueHost.inspect/findActivity/submit/submitEffect`；服务只缓存 tick 游标，测试通过内存假宿主集成真实 Framework |
| `presentation` 走宿主 effect | `TechniqueService` 按事件回执下发 `EffectIntent`；不执行世界变化，不提供正式表现内容 |
| 预输入、展示投影不做 | 无对应消息、队列或接口；忙碌和冷却直接拒绝 |

KISS/DRY/YAGNI 的落点是复用 Framework 库存算术、有效能力、原子活动和持久回执；
本库只维护声明式定义和必要编排。宿主接口隔离身体所有权，绑定、目录、存档和施放各自承担单一职责。

返工涉及文件：

| 文件 | 改动 |
|---|---|
| `fabric/.../TechniqueFabric.java` | 接入实体卸载，公开唯一服务入口，删除静态目录安装，命令使用服务目录和标识符参数 |
| `server/.../TechniqueService.java` | 合并推进路径，确认后更新实际游标，缓存待确认结果，永久拒绝转入独立清理，重放不重置游标 |
| `server/.../TechniqueCasting.java` | `AdvanceResult.advancedMillis`，统一构造终止回执，清理全限定名和布尔值注释 |
| `server/.../TechniqueHostDirectory.java`、`TechniqueRegistry.java`、`TechniqueProtocol.java` | 使用 import，补齐方法意图注释 |
| `server/.../MemoryHost.java`、`TechniqueStage34Test.java`、`TechniqueStage5Test.java` | 测试辅助 import、实际推进时刻断言、失联/重试/去重/方向/知识回归 |
| `fabric/src/gametest/.../TechniqueGameTestHost.java`、`TechniqueServiceGameTest.java`、`resources/fabric.mod.json` | 仅测试源集的目录和宿主 entrypoint，真实 Fabric 生命周期旅程 |
| `docs/wiki/Host-Integration.md`、`Home.md`、`Verification.md` | 公开入口示例、重试与重启语义、命令及验收映射 |

阶段 5 历史运行记录（2026-10-28；当前验收见下方阶段 6b）：

| 命令或检查 | 结果 |
|---|---|
| `./gradlew :server:test :fabric:compileGametestJava --console=plain` | 服务端测试与 GameTest 编译通过 |
| `./gradlew :fabric:runGametest --console=plain` | 首轮发现命令无法解析命名空间 ID；改用 `ResourceLocationArgument` 后 4 条全部通过 |
| `./gradlew test build --console=plain` | 最终构建通过，包含共享协议、服务端测试、Fabric 模组和打包 |
| 测试 XML 汇总 | 64 项，0 失败、0 错误、0 跳过；阶段 5 共 17 项 |
| `python3 "scripts/check_docs.py"` | 7 个页面链接全部通过；构建的 `checkDocs` 也通过 |
| `git diff --check` | 通过 |

返工修复卸载接线、服务访问、会话失联清理、效果游标与重试；另修复命令不能解析带冒号 ID 的实际缺陷。
没有修改 Framework，也没有提交或推送。

代码形状自查：第 4 条，`TechniqueFabric.learn` 复用服务启动时的目录；
第 9 条，拆开 `TechniqueStage5Test` 中过长的学会与 tick 测试，提取 `TechniqueCasting.stop` 统一终止活动构造；
第 10 条，改写服务、宿主目录中的抽象比喻注释；第 11 条，`TechniqueCasting`、`TechniqueHostDirectory`、
`TechniqueRegistry`、`TechniqueProtocol`、`MemoryHost` 与 `TechniqueFabric` 的行内全限定名改为 import，
`setReplayed(true)` 使用正常写法。`TechniqueMessages.*` 与相邻核心文件既有写法一致，保留。
新增 builder 每行一个 setter，无嵌套 builder、自有布尔参数、复杂三元或超过 120 列的新代码。

未检查的内容：真实宿主授权/持久存储、Human 或 Threatening 的实体接线、真实实体朝向/冲量/表现执行、
玩家真实断线事件、异常退出后的实际宿主恢复、性能基准、Windows 实机执行和正式内容平衡。
Fabric GameTest 验证真实实体卸载事件、服务端 tick 与命令，但身体存储和效果执行器仍是内存测试宿主。
Framework 的现有接口限制及宿主责任见 [宿主与施放流程](Host-Integration.md)。

## 阶段 6a：持物招式的协议与目录

实现依据为 `technique-stage6a.md` 和 `door-breaker-design-2026-10-28.md`，
以设计稿末尾用户决定为准。目录声明、起手判定与意图分发归本库，物品和世界执行仍归宿主。
书卷索引与持物判定各自独立；卷与单式复用一次原子学习提交，清理复用稳定效果身份，
避免额外卷进度、物品注册表依赖和重复清理逻辑。

下表中未写类名的测试均属于 `TechniqueStage6aTest`：

| 交接要求 | 测试名及验证内容 |
|---|---|
| 正常收势开始冷却 | `TechniqueCastingTest.cooldownAndSnapshotRoundTripUseExactBodyTimeBoundary`：收势加冷却的精确身体时钟边界与快照往返 |
| 打断时开始冷却、费用不退 | `TechniqueCastingTest.warmupDamageInterruptsWithoutRefundAndRetainsCooldown`、`TechniqueCastingTest.unloadAndDisconnectCancelWithoutRefundAndKeepCooldown`；`damageUnloadAndDisconnectUnlockAndStopWarmupPresentation` 另核对实际终止时刻加冷却 |
| 学整卷、前置拒绝、单式记录 | `volumeLearningIsAtomicChecksPrerequisiteAndKeepsSingleTechniqueRecords`：缺前置或宿主拒绝均不部分写入，整卷只提交一次、通知一次、重复幂等 |
| 单式学习命令兼容 | `singleTechniqueLearningRemainsAvailableAndPartialVolumeDoesNotMeetPrerequisite`：单式仍可学，前置卷全部精确式版本已学才满足 |
| 双手空与装物容器拒绝 | `emptyHandsAndFilledContainersRejectBeforePaymentWithPlayerReasons`：玩家原因分别为「需要手持物品」「这件物品不能拿来劈」，无扣费或冷却写入 |
| 书级选手顺序 | `handSelectionUsesCategoryThenWearThenMainHandAndSupportsOffHandOnly`：仅副手、泥土对木剑、钻斧对铁斧选副手；双铁斧选主手；无耐久与装物候选正确排除或排后，左主手侧不颠倒逻辑身份 |
| 起手锁、正常收势解锁 | `movementLocksImmediatelyAndUnlocksAtRecoveryBoundary`：起手立即 LOCK，收势边界 UNLOCK，重复 tick 不重发 |
| 打断、卸载、下线解锁及表现清理 | `damageUnloadAndDisconnectUnlockAndStopWarmupPresentation`：各入口 STOP/UNLOCK、费用保留、动作释放 |
| 卸载目录和恢复仍清理 | `catalogRemovalAndSavedBodyRecoveryStillUnlockAndStopPresentation`：无目录也清理，死体恢复只发 STOP/UNLOCK、不补冲量或 START，重复恢复幂等 |
| INPUT 默认方向 | `inputFallbackSupportsBackwardKeepsForwardDefaultAndPreservesInput`：缺省与显式零输入使用声明的 BACKWARD，未声明 FORWARD，非零输入原样传递 |
| 剪辑起播毫秒 | `damageUnloadAndDisconnectUnlockAndStopWarmupPresentation` 核对指定起播时刻；`identicalPresentationsHaveIndependentStopsAndStartTimeDefaultsToZero` 核对缺省为 0、同内容表现各自幂等 |
| 起手打断停止一次性表现 | `oncePresentationStopsWhenWarmupIsInterrupted`：PLAY_ONCE 后仍发 STOP，STOP 起播时间为 0 |
| 九秒与三次冲量 | `nineSecondCastDispatchesThreeImpulsesWithStableIndependentIds`：九秒总长、跨 tick 三次冲量稳定独立 ID；`everyCastPhaseAcceptsNineSeconds`：三段各九秒合法 |
| 打断不撤销曲线 | `interruptedCurveStillBlocksRecastUntilFullCurveEnd`：打断后冷却可提早结束，同式仍需等待已提交完整曲线到期 |

公开目录与协议的必要边界回归：

| 测试类 | 测试名 |
|---|---|
| `TechniqueHeldCatalogTest` | `groupedCatalogRoundTripsAndPublishesImmutableExactMembership`：目录往返、精确版本归属、不可变列表 |
| `TechniqueHeldCatalogTest` | `rejectsUnknownExactVersionsDuplicateMembershipAndAmbiguousCommandIds`：未知版本、多卷归属、书卷式身份冲突 |
| `TechniqueHeldCatalogTest` | `rejectsCrossBookAndCyclicPrerequisites`：跨书、未知及有环前置 |
| `TechniqueHeldCatalogTest` | `rejectsIncompleteMatchersWrongDamageUnitsAndUnknownRuleEnums`：缺兜底、错单位、未知枚举、非法顺序或零耐久倍率 |
| `TechniqueProtocolTest` | `inspectionRoundTripsBothHandsAndRejectsIncompleteItemFacts`：双手字节往返、缺事实或主手侧拒绝 |
| `TechniqueProtocolTest` | `movementAndClipIntentsRoundTripAndRejectInvalidActionsOrTimes`：LOCK/UNLOCK、起播时间往返，非法动作/负时间/STOP 带起播时间拒绝 |

Fabric GameTest 保留上述四项，并增加三项真实服务端流程，全部属于 `TechniqueServiceGameTest`：

| 测试名 | 验证内容 |
|---|---|
| `volumeCommandSelectsOffHandLocksAndUnlocksOnServerTick` | 前置卷拒绝、上卷/下卷学习命令、选副手、起手 LOCK、真实 tick 收势 UNLOCK |
| `unclaimedSavedCastRecoversWithCleanupOnly` | 未认领的存活存档恢复只清理表现，释放动作且保留费用、知识与冷却，二次恢复不重发 |
| `unclaimedDeadCastRecoversWithoutRefund` | 未认领死体恢复走可信进度入口，不复活、不退费，只清理残留表现和施放 |

涉及文件（路径中 `...` 表示对应 Java 包）：

| 文件 | 改动 |
|---|---|
| `shared/src/main/proto/kizuna/meridian/technique/v1/technique.proto` | 书卷、持物类别与双手事实、选手、BACKWARD、剪辑起播、移动限速、效果身份及清理存档字段 |
| `server/src/main/java/.../TechniqueGroups.java`、`TechniqueHeldItems.java`（新增） | 不可变分组索引、引用与前置校验；规则校验、候选过滤与选手 |
| `server/src/main/java/.../TechniqueRegistry.java`、`TechniqueDefinitions.java` | 分组查询、书级规则来源、曲线与默认方向/起播校验 |
| `server/src/main/java/.../TechniqueActivities.java`、`TechniqueCasting.java`、`TechniqueScheduler.java` | 收势冷却与提前终止重算、曲线期限、起手选手、LOCK/UNLOCK、表现 STOP、稳定效果 ID |
| `server/src/main/java/.../TechniqueState.java`、`TechniqueErrors.java` | 新存档字段校验与两项玩家失败原因 |
| `server/src/main/java/.../TechniqueHost.java`、`TechniqueRecoveryHost.java`、`TechniqueHostDirectory.java`、`TechniqueProtocol.java` | 同次 inspect 身体及双手事实、字节回执校验、恢复清理效果入口 |
| `server/src/main/java/.../TechniqueService.java`、`fabric/src/main/java/.../TechniqueFabric.java` | 原子整卷学习与命令、稳定效果请求、立即移动限速、恢复 STOP/UNLOCK |
| `server/src/test/java/.../HeldTechniqueFixtures.java`、`TechniqueStage6aTest.java`、`TechniqueHeldCatalogTest.java`（新增） | 仅测试源集的书卷与持物样例、关键流程和目录契约回归 |
| `server/src/test/java/.../MemoryHost.java`、`TechniqueProtocolTest.java`、`TechniqueCastingTest.java`、`TechniqueRecoveryTest.java`、`TechniqueRegistryTest.java`、`TechniqueStage34Test.java`、`TechniqueStage5Test.java` | 适配双手事实、新冷却与清理语义，保留既有原子性、幂等与完整曲线保护 |
| `fabric/src/gametest/java/.../TechniqueGameTestHost.java`、`TechniqueServiceGameTest.java` | 测试目录及双手宿主接线、卷学习/选手/移动限速、存活与死体恢复验收 |
| `docs/wiki/Contracts.md`、`Host-Integration.md`、`Home.md`、`Verification.md` | 公开契约、宿主必需更新、范围与逐项验收记录 |

阶段 6a 历史运行记录（2026-10-28；当前验收见下方阶段 6b）：

| 命令或检查 | 结果 |
|---|---|
| `./gradlew :server:test :fabric:compileGametestJava --console=plain` | 服务端测试与 GameTest 编译通过 |
| `./gradlew test build :fabric:runGametest --console=plain --rerun-tasks` | 强制重跑成功，42 秒，36 个任务全部执行；7 项 GameTest 全部通过；日志 `/tmp/technique-stage6a-validation.log` |
| 服务端测试 XML 汇总 | 92 项，0 失败、0 错误、0 跳过；`TechniqueStage6aTest` 13 项，`TechniqueHeldCatalogTest` 4 项 |
| `python3 "scripts/check_docs.py"` | 7 个页面通过；构建内 `checkDocs` 同样通过 |
| `git diff --check` | 通过 |

编译使用 Java 17 toolchain 与 `release=17`；此次 GameTest 实际运行在 Java 21。
早期 GameTest 曾有两条恢复断言要求没有效果；更新为只允许清理 STOP 后，7 项全部通过。

Human 兼容性：`TechniqueRecoveryHost.submitEffect(EffectSubmission)` 是新增的必需接口方法。
Damage §5 曾报告 Human 的旧恢复实现缺少此方法，默认 composite build 又直接引用相邻 Technique，
因此发生编译失败；这是新增抽象方法带来的源码不兼容。6b 收尾时只读可见 Human 工作区已有
`HumanTechniqueRecovery.submitEffect`，不能继续把该旧错误当作当前构建结论；本轮没有运行或修改 Human。
Human 需提供允许未认领身体和死体执行幂等 STOP/UNLOCK 的恢复适配器，并补齐 inspect 双手事实、移动限速、
BACKWARD 和表现起播/停止的实体执行；恢复不能经要求已认领的 `exchange` 回调。
详细顺序与失败处理见 [宿主与施放流程](Host-Integration.md)。

设计稿核对结果：

- 文末决定覆盖 §11 的起手冷却旧说明；现在从收势结束算，内容冷却值不再加整式时长。
- 文末决定取消首版 `protected` 和 BS3D 数据文件；装着东西的物品仍拒绝使用。
- 同一工具类别的剑/三叉戟与其他工具致伤不同，内容需拆标签类别、共用选手优先级；方块物品类别同样由宿主标签表示。
- §4.1 的「起跳前被打断，曲线还没加上，不虚脱」与本库起手原子提交全部延迟修正、曲线必须完整运行的约束冲突。
  本次沿用库约束；未来内容落地前需决定是否另设计延迟提交机制。没有提前撤销修正。
- 剪辑起播毫秒只校验合法范围，不读取动画资源；剪辑长度、起播与动作对齐由内容和宿主负责。
- 本轮没有发现必须新增的 Framework 接口；既有做功无力量字段、无全局标签枚举、死体拒绝活动等限制仍按宿主文档处理。

阶段 6a 当时未实现或未检查：`weapon_strike`、`break_blocks`、`follow_up`、新 AI 情境、破门斩正式目录、
Human/Threatening 实体执行、Damage 接线、战斗 UI、真实物品攻击属性/附魔/火焰/击退/扣耐久/打击时换物检查、
真实客户端移速同步/动画/冲量、玩家真实断线、实际持久存储与异常进程退出恢复、性能、Windows 实机和正式数值平衡。
GameTest 使用真实 Minecraft 1.20.1 服务端生命周期、命令和 tick，但身体与效果仍由内存测试宿主提供。
没有修改 Framework、Human 或其他依赖，没有提交或推送。

代码形状自查：第 1 条，展开 `HeldTechniqueFixtures.presentationEffect` 与
`TechniqueStage6aTest.impulseEffect` 的 builder；第 4 条，`TechniqueGroups.indexBooks/indexVolumes` 复用身份局部值；
第 9 条，拆开 `TechniqueGroups` 的书卷建索引阶段、`TechniqueState` 的记录/结束时刻校验、
`TechniqueCasting.bindCast` 的绑定与选手阶段、`TechniqueStage6aTest.assertSelectedHands` 的逐组施放断言。
本次新增方法均有中文意图注释，新增代码无超过 120 列、自有布尔参数、复杂三元或嵌套 builder。

## 阶段 6b：持物效果与承接

实现依据为 `technique-stage6b.md` 与同一份破门斩设计稿，保留未提交的全部 6a 改动。
本库产出带身体时刻、稳定效果身份、起手选定手和书级规则的打击/劈门意图；几何、伤害和世界执行归宿主。
承接由玩家与 AI 共用一个窗口判定，动作替换、支付和后式进度仍复用 Framework 单次原子活动。
命中预算是无状态 helper，最近施放写入既有附属进度，不增加世界依赖或独立身体状态。

下表中未写类名的测试均属于 `TechniqueStage6bTest`：

| 交接要求 | 测试名及验证内容 |
|---|---|
| 打击、劈门参数和时序 | `strikesAndDoorsKeepParametersScheduledTimeAndStableReceiptsAcrossLateTicks`：字节宿主收到形状、朝向、倍率、盾、致伤、双手、耐久及门范围；迟到 tick 保留原时刻，重试不重复 |
| 横扫同组目标一次与合计上限 | `sweepDeduplicatesWithinGroupAndSharesCastTargetBudgetAcrossSectors`：前后半圈同组共享五目标预算、候选去重、单体每击上限 |
| 连门三刀同目标 | `chainStrikesInDifferentGroupsEachHitTheSameTargetAfterTimedDispatch`：通过服务、字节宿主逐击分发三个不同组，每组均选中同一目标，组内重复仍拒绝 |
| 跨组不同目标预算 | `differentGroupsShareDistinctTargetBudgetWithoutCountingRepeatHitsTwice`：整式不同目标满额后拒绝新目标，但允许跨组重击已有目标；新 cast 不受旧记录影响 |
| 非法去重组 | `missingOrMalformedDedupGroupsAreRejectedAtCatalogAndEffectBoundaries`：缺失、空白、非法及超长组名在目录和意图边界拒绝 |
| 窗内原子承接、曲线与冷却 | `followUpAtomicallyReplacesActionKeepsCurvesAndNormalCooldownAndSkipsPreparation`：前式立即停动作、费用共两次、旧曲线和正常冷却不变；后式缩短起手、跳过预备冲量、持续表现偏移起播 |
| 窗口精确边界 | `followUpWindowIncludesBothEdgesAndRejectsOutsideWithoutPayment`：两端包含，前后各一毫秒拒绝且不扣费 |
| 只能承接与普通 AUTO | `onlyFollowUpRejectsStandaloneAndOrdinaryTechniqueCanStartOutsideWindow`：只能承接式单独拒绝；普通式窗外可独立起手 |
| Framework 原子拒绝 | `rejectedFollowUpPreservesPreviousActionCurvesAndPaymentAndCanRetry`：不先取消前式、不部分付费，原请求可重试 |
| 前式效果待确认 | `unconfirmedPredecessorEffectsBlockFollowUpUntilAcknowledged`：旧效果暂拒时阻止替换，确认原批次后才支付后式 |
| 缩短起手后的打断 | `followUpInterruptUsesShortenedWarmupBoundaryAndDoesNotEmitStrikesEarly`：首击前一毫秒可打断且无后式打击，边界上伤害不取消，随后 tick 发首击 |
| 历史与快照 | `finishedHistorySurvivesSnapshotAndCancelledHistoryCannotOpenWindow`：正常结束历史可跨存档承接，取消历史不能开窗 |
| 两边重放幂等 | `replayOfEitherCastDoesNotRestoreOldSessionOrDuplicateEffectsAndFees`：重放前后式不恢复旧游标、不重复效果或费用 |
| 恢复清理 | `recoveryCleansBothSidesOfCommittedFollowUpWithoutReplayingStrikes`：原子承接后的前后式均可停止/解锁，不补攻击 |
| AI 情境 | `aiFactsMultiplyScoresAndLongWarmupIsExcludedWhenFarOrUnderAttack`：四类事实权重相乘，远距离与挨打时排除长起手 |
| AI 承接 | `aiOnlyConsidersFollowUpWithinWindowAndUsesShortenedWarmup`：仅窗内可选，使用缩短后的起手，并检查冷却与曲线到期 |
| 非法契约 | `malformedCombatParametersSequencesReferencesAndProgressAreRejected`：缺显式值、错误单位/形状/数量/枚举、无持物书、非法起播与历史拒绝 |
| 整式一致性 | `inconsistentStrikeBudgetsDurabilityAndLastStrikeAreRejected`：错误末击、跨击预算和耐久基数拒绝 |
| 引用与显式空值 | `unknownPredecessorsAndExplicitEmptyCombatWeightsAreRejected`：不存在的精确前式版本与显式空 AI 权重拒绝 |
| 真实 Fabric tick | `TechniqueServiceGameTest.combatFollowUpDispatchesTimedStrikesAndDoorsOnServerTicks`：目录入口、整卷学习、字节宿主、原子承接、后式首/末击、劈门时刻及收势解锁 |

改动文件（6b 在 6a 上的增量；上方保留 6a 文件说明）：

| 文件 | 改动 |
|---|---|
| `shared/src/main/proto/.../technique.proto` | `WeaponStrike/BreakBlocks`、意图分支与身体时刻、承接声明/请求入口/最近施放、AI 战斗提示、当前施放清理事实 |
| `server/.../TechniqueCombatEffects.java`、`TechniqueStrikeTargets.java` | 参数和整式末击/预算/耐久校验，宿主可复用的跨击目标预算 |
| `server/.../TechniqueFollowUps.java` | 精确前式引用、含边界的窗口、AUTO/强制承接、可信历史 |
| `server/.../TechniqueActivities.java`、`TechniqueCasting.java` | 起播投影、原子停旧起新、保存最近施放，维持前式冷却/曲线 |
| `server/.../TechniqueScheduler.java`、`TechniqueService.java` | 带时刻意图、前式先确认再替换、先清理再起新表现、会话回执与快照修复 |
| `server/.../TechniqueDefinitions.java`、`TechniqueRegistry.java`、`TechniqueProtocol.java`、`TechniqueState.java`、`TechniqueErrors.java` | 目录、字节与进度边界校验，玩家失败原因 |
| `server/.../TechniqueSelector.java` | 四类事实打分钩子、起手距离/受击限制、共享窗口和公开 `score` |
| `server/src/test/java/.../CombatTechniqueFixtures.java`、`TechniqueStage6bTest.java`、`MemoryHost.java` | 仅测试内容、19 项必要回归、暂拒效果及回执重试 |
| `fabric/src/gametest/java/.../TechniqueGameTestHost.java`、`TechniqueServiceGameTest.java` | 独立战斗测试目录和真实服务端 tick 旅程 |
| `docs/wiki/Contracts.md`、`Host-Integration.md`、`Home.md`、`Verification.md` | 参数、窗口、执行责任、宿主迁移与验收映射 |

6b 初次运行记录（2026-10-28；按组去重修正的当前验收见文末）：

| 命令或检查 | 结果 |
|---|---|
| `./gradlew test build :fabric:runGametest --console=plain --rerun-tasks` | `BUILD SUCCESSFUL`，37 秒，36 个任务全部执行；日志 `/tmp/technique-stage6b-validation.log` |
| 服务端测试 XML 汇总 | 11 个测试类，108 项，0 失败、0 错误、0 跳过；其中 `TechniqueStage6bTest` 16 项全部通过 |
| GameTest 日志及 XML | 8 项全部通过，含上表真实 tick 承接旅程；报告 `fabric/build/gametest/build/gametest-results.xml` |
| `python3 "scripts/check_docs.py"` | 7 个页面通过；完整构建内 `checkDocs` 同样通过 |
| `git diff --check` | 通过 |
| 相邻 Framework | HEAD `e2859475afe6ef4d948f35052e1e112c051ab737`，工作区干净；没有修改依赖源码 |
| 当前 Technique | HEAD 保持 `89e37ec0a35748297d7ffd281231d2eb9537065f`，保留 6a 与 6b 未提交改动；未提交、未推送 |

编译使用 Java 17 toolchain 与 `release=17`；GameTest 实际运行在 Java 21。

设计稿问题与选择：

- §4.1 要求起跳前打断不虚脱，但现有库在起手原子提交全部延迟曲线，且不能提前移除。本轮保持行为：
  打断不退费、不撤销曲线，已提交的延迟虚脱仍会发生。方案 A 是修改内容/设计，接受此行为；
  方案 B 是以后另行设计起跳边界的延迟原子曲线提交、幂等与失败语义。本轮不实现延迟机制或 `remove_modifiers`。
- 设计稿文末「6b 设计稿修正」已将去重范围改为组：横扫同组，连门三刀各组，允许同目标挨三刀。
  该矛盾已消除；整式不同目标上限和整式一次末击耐久仍保留。
- 冷却从收势结束算、首版不做 `protected`，以用户决定为准；装物仍拒绝。数值是占位，
  形状与时刻仅校验参数，未核对真实动画资源或实际命中帧。

宿主迁移：6a 的 `TechniqueRecoveryHost.submitEffect(EffectSubmission)` 仍是新增必需方法，
旧宿主缺少实现会使默认引用本地 Technique 的构建编译失败。收尾只读核对时，Human 工作区已存在
实现该方法的 `HumanTechniqueRecovery`，本轮未运行 Human 构建，未确认其完整集成结果。
Human 需在未认领/死体恢复路径幂等执行 STOP/UNLOCK，先清效果、再提交恢复进度、最后认领；
补齐 inspect 双手事实、移动限速、BACKWARD、表现起播/停止，并实现 6b 两个新 oneof 分支、
命中记录、末击耐久、Damage 出手侧/双手做功和玩家破门权限，不能空实现或静默确认。
本库没有修改或构建 Human、Damage、Threatening；接线细节见 [宿主与施放流程](Host-Integration.md)。

未实现或未检查：正式破门斩目录、真实选目标/世界伤害/盾/物品消耗/附魔/耐久、玩家权限和生物破门、
Human/Damage/Threatening 接线、动画和 UI、真实客户端同步、实际持久化事务/进程崩溃恢复、
真实玩家断线、性能、Windows 实机与正式数值平衡。GameTest 使用真实 Minecraft 1.20.1 生命周期和 tick，
但身体与效果由内存测试宿主提供。

代码形状自查：第 2 条，拆开 `TechniqueStage6bTest` 的内层承接 builder；第 4 条，
`TechniqueCombatEffects`、`TechniqueFollowUps`、`TechniqueScheduler.due/project` 及 GameTest 复用局部值；
第 5 条，`TechniqueService.cast` 的宿主选择和 `TechniqueActivities` 的状态构造不复用变量加工；
第 7 条，`CombatTechniqueFixtures` 的收势窗口比例命名；第 9 条，拆出
`TechniqueActivities.startedProgress/predecessorCleanup`、`TechniqueCombatEffects` 的分组校验、
`TechniqueScheduler.weaponStrike/breakBlocks`、`TechniqueProtocol.validatePresentationIntent`，并拆开超长行。

## 持物打击按组去重修正

依据设计稿文末「6b 设计稿修正」和用户决定，`WeaponStrike.dedup_group=17` 为必填局部标识。
`TechniqueStrikeTargets.select` 接收当前 cast 的分组命中映射，同组每目标一次、跨组可重击；
`cast_target_limit` 仍约束整式不同目标的并集，不将同目标的重复命中计为新目标。

改动文件为 `technique.proto`、`TechniqueCombatEffects.java`、`TechniqueStrikeTargets.java`、
`CombatTechniqueFixtures.java`、`TechniqueStage6bTest.java`、`TechniqueServiceGameTest.java`，
以及本页、`Contracts.md`、`Host-Integration.md`。测试名与覆盖内容已列入上方验收表；
既有 GameTest 额外检查首击组名和前后半圈同组在真实 Fabric tick 中保留。

宿主须迁移单集合 helper 调用、逐组保存命中，并为现有持物打击内容补上组名；不提供缺失组名的降级行为。
真实选目标、伤害和世界执行仍由宿主提供，本轮未修改或构建 Human、Damage、Threatening。

实际运行记录（2026-10-28）：

| 命令或检查 | 结果 |
|---|---|
| `./gradlew :server:test --tests "dev.kizuna.meridian.technique.server.TechniqueStage6bTest" :fabric:compileGametestJava --console=plain` | 定向测试与 GameTest 编译成功；日志 `/tmp/technique-stage6b-dedup-targeted.log` |
| `./gradlew test build :fabric:runGametest --console=plain --rerun-tasks` | `BUILD SUCCESSFUL`，37 秒，36 个任务全部执行；日志 `/tmp/technique-stage6b-dedup-validation.log` |
| 服务端测试 XML 汇总 | 111 项，0 失败、0 错误、0 跳过；其中 `TechniqueStage6bTest` 19 项全部通过 |
| GameTest 日志及 XML | 8 项全部通过；报告 `fabric/build/gametest/build/gametest-results.xml` |
| `python3 "scripts/check_docs.py"` | 7 个页面通过；完整构建内 `checkDocs` 同样通过 |
| `git diff --check` | 通过 |
| Git 状态 | Technique HEAD 保持 `89e37ec0a35748297d7ffd281231d2eb9537065f`；Framework 工作区干净；未提交、未推送 |

代码形状自查：第 4 条，`TechniqueServiceGameTest.assertCombatFirstHit/assertCombatLastHit`
复用打击参数局部变量，`TechniqueStrikeTargets.select` 缓存循环使用的目标上限；
第 5 条，`CombatTechniqueFixtures.chain` 的末击类型通过分支单次赋值；
第 7 条，`CombatTechniqueFixtures` 为三刀次数、间隔和单体角度命名常量。

## 交叉审阅返工（2026-10-28）

T1 终止施放时分别保存身体取消与每个 STOP/UNLOCK 的完成状态。目标消失等永久业务拒绝不再丢会话；
部分业务批次不会重放，清理拒绝会使用原请求身份重试。`TechniqueCleanupTest` 覆盖
`missingTargetTerminatesAndCleansStartedEffects`、`partialBatchSuccessRetainsStopWithoutReplayingBusiness`、
`cleanupRejectedAfterBodyFinishRetriesWithoutActiveCast`、`unloadRetriesRejectedCleanupAndKeepsStableIdentities`
和 `startCallbackQueuesCancelUntilDeliveryReturns`。服务推进期间收到取消会排队，在当前回调返回后处理，
同实体不会递归分发。

T2 只有正常 FINISHED 沿用原收势边界；取消与中断恢复按实际终止时刻计算冷却。
`TechniqueRecoveryTest` 使用独立具名时刻和预期值覆盖活体/死体在原收势前后恢复，
另以 `lateNormalFinishKeepsOriginalRecoveryBoundary` 保留正常晚完成边界；
`TechniqueStage6aTest` 覆盖僵直打断时刻。

本轮最终验证命令与结果、服务端测试和 GameTest 计数见
`~/Code/Kizuna-remote-session/handoff/cross-review-rework-2026-10-28-result.md`。

## 迁移到 Minecraft 26.2（2026-10-31）

工具链改为 Java 25、Gradle 9.8.0 与不重映射的 Loom 1.19.3；Fabric 依赖改用 `implementation`，
本地运行依赖由 `modLocalRuntime` 改为 `localRuntime`。玩法行为不变，只按 26.2 的接口改写：
网络改走 Framework 的 `ProtobufChannel`，物品、声音、状态效果与实体类型改用 `Identifier`、`Holder`
与 `EntityTypes`，卷轴阅读界面改为 26.2 的绘制收集阶段，跃步落点描边改在 `COLLECT_SUBMITS` 提交。

| 命令 | 结果 |
|---|---|
| `./gradlew test build :fabric:runGametest --console=plain` | JUnit 196/196（服务端 180、fabric 16）；GameTest 18/18，另有原版 `always_pass` |
| `python3 scripts/check_docs.py` | 通过 |
| `xvfb-run -a ./gradlew -PscrollFullAssembly :fabric:runClientTest`（11-01） | 卷轴阅读 `success=true`，截图 6 张、合上检查 7 次 |
| `xvfb-run -a ./gradlew -PscrollFullAssembly :fabric:runLeapClientTest`（11-01） | 跃步 `success=true`，最大回拉 0、无传送包，三跳落点各前进 6 格 |

GameTest 改用 Fabric 的 `@GameTest`。26.2 的 `performPrefixedCommand` 不再返回结果，测试经
`GameTestCommands` 从来源的结果回调取得；物品使用成功会附带手中物品，断言改为判断服务端成功且不挥手。
两项客户端验收在装配脚本迁移后补跑（上表 11-01 两行）：完整装配含 Human 客户端，`ALSOFT_DRIVERS=null`、
llvmpipe 软件渲染，新运行目录先跑 `:fabric:runGametest` 生成世界，客户端目录预置 `options.txt`
（`onboardAccessibility:false`，跳过首启无障碍引导）。

### 下游共用的 GameTest 夹具

`fabric` 模块开 `testFixtures`，放下游 GameTest 共用的 26.2 假玩家，不进正式 JAR。
`TestPlayers.detached` 按原版登录同一套 `CommonListenerCookie` 构造 `ServerPlayer` 与正式网络处理器，
不进玩家列表；`TestConnection` 只换传输层，发往客户端的包直接丢弃。
`TestPlayers.logIn` 走原版进服入口：先读同一 UUID 的玩家存档（26.2 的玩家列表不再自己读档），
再进玩家列表、触发进服与 Fabric 网络事件，最后按客户端已加载完世界处理（上报前原版视玩家无敌并忽略交互）。
配套的 `RecordingConnection` 记录发往客户端的包、照常执行发送回调，构造时预先声明客户端能收的通道；
`disconnectFromClient` 模拟客户端退出，服务端已先踢出时不重复处理断线。
下游在 GameTest 源码集写 `testFixtures('dev.kizuna.meridian.technique:fabric')`，经源码组合解析。
夹具按实际用法逐步补充：Vanilla Damage 的挥手包用例用 `detached`，Human 的整套宿主用例用 `logIn`。

## 盾挡朝向与 26.2 原版对齐（2026-11-02）

`TechniqueWorldEffects` 判断是否破盾、给盾冷却前，改由 `TechniqueWorldBlocking.facesSource` 判正面：
与原版 `LivingEntity.applyItemBlocking` 同一套算法，取头部水平朝向（俯仰按 0）与伤害来源水平方向的夹角，
不超过格挡物品各减伤项的水平格挡角即为正面。旧算法用含俯仰的视线向量、水平点积严格大于 0，
对原版盾只在来源正好位于正侧方 90° 时与原版不同（原版算挡住，旧算法不算）。

`TechniqueWorldBlockingGameTest` 三例都同时断言功法判定和原版 `applyItemBlocking` 的结果：
头朝来源、身体背对时挡住；头背对、身体朝向时不挡；正侧方 90° 挡住。
红灯：换回旧算法时只有正侧方一例失败。

| 命令 | 结果 |
|---|---|
| `./gradlew test build :fabric:runGametest --console=plain` | fabric JUnit 16/16（服务端源码未改，`:server:test` 为 UP-TO-DATE）；GameTest 21/21，另有原版 `always_pass` |

## 敌我预筛交给宿主（2026-11-02）

`TechniqueWorldTargets` 不再按 `actor.isAlliedTo(target)` 排除目标，只排除自己、死亡、旁观者、视线外和标记盔甲架，
敌我（含同队、原版同伴）全部由宿主谓词决定。类人生物宿主据此可以打「同伴但正是我的敌人」的目标；
Human 的玩家谓词改为自己排除原版同伴，玩家侧行为不变。

`alliedTargetIsLeftToHostPredicate` 把两只实体放进同一队伍，宿主谓词接受目标时照常入选。
红灯：旧预筛下该例失败。

| 命令 | 结果 |
|---|---|
| `./gradlew test build :fabric:runGametest --console=plain` | fabric JUnit 16/16（服务端源码未改，`:server:test` 为 UP-TO-DATE）；GameTest 22/22，另有原版 `always_pass` |

## 客户端验收跟随每局新建的世界（2026-11-02）

`runGametest` 每局先删掉 `build/gametest/world` 再按平坦预设新建世界，出生点随机且远离原点。
跃步客户端验收原先把跑道铺在原点再传送过去，旧世界里原点区块早已存在才通过；新世界里客户端要等
原点区块生成并下发，等待超过轻身持续时间。跑道改铺在玩家进世界的出生点，传送只在已加载区块内移动。

| 命令 | 结果 |
|---|---|
| `xvfb-run -a ./gradlew -PscrollFullAssembly :fabric:runLeapClientTest`（改前，新世界） | 失败：客户端缺少合法落点标记，脚下为 `void_air` |
| 同上（改后，新世界） | `success=true`，最大回拉 0、无传送包，三跳落点各前进 6 格 |
| `./gradlew :fabric:runGametest` 后再跑同上（保留上一局的客户端存档目录） | `success=true`，出生点换到另一处，三跳落点各前进 6 格 |

完整装配会同时加载 Human 宿主，`:fabric:runGametest` 不加 `-PscrollFullAssembly`，否则玩家被两个功法宿主认领
（`MultipleHostsException`），6 项 GameTest 失败。

两项客户端验收不再依赖手工预置的 `options.txt`：`runClientTest` 与 `runLeapClientTest` 启动前把各自运行目录的
`onboardAccessibility` 写成 `false`，文件不存在时新建。验证时先把两份残留文件改回 `true` 再各跑一次，
卷轴阅读 `success=true`（截图 6 张、合上检查 7 次）、跃步 `success=true`，两份文件跑完均为 `false`。
