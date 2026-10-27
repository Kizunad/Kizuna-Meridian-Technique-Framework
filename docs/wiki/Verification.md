# 规则、测试与交付记录

实现依据为 2026-10-24 的功法方案、2026-10-26 的框架更新，以及
`technique-combat-plans-2026-10-26.md` 中新版 `plans/06-Technique-Framework.md`。
当前交付阶段 1–5；Fabric 模组只负责服务端装配，身体和世界效果仍由独立宿主入口提供。
依赖核对时 Framework 为 `216710d03ff9bb0e4ae13894ab9b0a8cf7be9a45`，未修改其受版本控制文件。

测试全部属于公开契约、关键业务流程或模块交互的必要整体回归。
测试功法和双侧/触须身体仅在 `server/src/test/java/` 与 `fabric/src/gametest/java/`，
内存假宿主调用真实 Framework 处理器和存储。

| 规则 | 测试类与测试名 |
|---|---|
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
| 提前结束只停止已启动的持续表现 | `TechniqueStage34Test.earlyTerminationStopsStartedContinuousPresentation`, `TechniqueStage34Test.earlyTerminationDoesNotStopPresentationBeforeItsStart` |
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
| 服务级伤害打断和卸载取消 | `TechniqueStage5Test.serviceDamageInterruptClearsActiveCastWithoutRefund`, `TechniqueStage5Test.serviceUnloadClearsActiveCastWithoutRefund` |
| 宿主回执索引重建后的活动重放 | `TechniqueStage5Test.activeCastReplaysWhenDirectoryReceiptIsRebuilt` |
| 未认领、身体不存在后停止每 tick 重试 | `TechniqueStage5Test.lostHostRemovesSessionInsteadOfRetryingEveryTick` |
| 效果或收势提交时失去认领也清理会话 | `TechniqueStage5Test.lostHostDuringEffectOrRecoveryRemovesSession` |
| 暂时拒绝重试、永久拒绝移除会话 | `TechniqueStage5Test.temporaryRejectionRetriesButPermanentRejectionDropsSession` |
| 相邻 inspect 间时钟前进不重复效果 | `TechniqueStage5Test.advancingHostClockDoesNotDispatchEffectTwice` |
| 部分效果成功及收势后失败仍重试原批次 | `TechniqueStage5Test.effectRetryPreservesBatchAfterPartialAcceptanceAndRecovery` |
| 同一请求重放不倒退效果游标 | `TechniqueStage5Test.castReplayDoesNotRewindEffectCursor` |
| 服务入口拒绝未知和未学功法 | `TechniqueStage5Test.serviceRejectsUnknownAndUnlearnedClientReferences` |

Fabric GameTest 全部使用真实服务器启动的 `TechniqueFabric.service()`，测试宿主和目录由 Fabric entrypoint 发现：

| 验收 | `TechniqueServiceGameTest` 测试名 |
|---|---|
| 学会命令写进度、`KNOWN_CHANGED`、`known`、真实 tick 下发冲量与表现 START/STOP、收势 | `learnCommandPublishesKnownChangedAndTicksToFinish` |
| 从公开服务入口调用伤害打断，取消不退费且保留冷却 | `damageInterruptCancels` |
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
| `server/.../TechniqueService.java` | 合并推进路径，确认后更新实际游标，缓存待确认结果，清理永久拒绝会话，重放不重置游标 |
| `server/.../TechniqueCasting.java` | `AdvanceResult.advancedMillis`，统一构造终止回执，清理全限定名和布尔值注释 |
| `server/.../TechniqueHostDirectory.java`、`TechniqueRegistry.java`、`TechniqueProtocol.java` | 使用 import，补齐方法意图注释 |
| `server/.../MemoryHost.java`、`TechniqueStage34Test.java`、`TechniqueStage5Test.java` | 测试辅助 import、实际推进时刻断言、失联/重试/去重/方向/知识回归 |
| `fabric/src/gametest/.../TechniqueGameTestHost.java`、`TechniqueServiceGameTest.java`、`resources/fabric.mod.json` | 仅测试源集的目录和宿主 entrypoint，真实 Fabric 生命周期旅程 |
| `docs/wiki/Host-Integration.md`、`Home.md`、`Verification.md` | 公开入口示例、重试与重启语义、命令及验收映射 |

实际运行记录（2026-10-28）：

| 命令或检查 | 结果 |
|---|---|
| `./gradlew :server:test :fabric:compileGametestJava --console=plain` | 服务端测试与 GameTest 编译通过 |
| `./gradlew :fabric:runGametest --console=plain` | 首轮发现命令无法解析命名空间 ID；改用 `IdentifierArgumentType` 后 4 条全部通过 |
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
