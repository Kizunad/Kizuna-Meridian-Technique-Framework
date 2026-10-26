# 规则、测试与交付记录

实现依据为 2026-10-24 的功法方案、2026-10-26 的框架更新，以及
`technique-combat-plans-2026-10-26.md` 中新版 `plans/06-Technique-Framework.md`。
当前仅交付阶段 1–2；协议和结构已包含用户补充要求。
依赖核对时 Framework 为 `216710d03ff9bb0e4ae13894ab9b0a8cf7be9a45`，未修改其受版本控制文件。

测试全部属于公开契约、关键业务流程或模块交互的必要整体回归。
测试功法和双侧/触须身体仅在 `server/src/test/java/`，内存假宿主调用真实 Framework 处理器和存储。

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
| 熟练度不改变费用；不执行修正、不发提前移除 | `TechniqueCastingTest.proficiencyDoesNotChangeCostsAndNoStageThreeEffectIsExecuted` |
| 玩家文本不含数值或时间单位 | `TechniqueCastingTest.playerFacingReasonsContainNoTimingOrNumericDetails` |
| 宿主 oneof 编号、三操作往返、表现交给 effect | `TechniqueProtocolTest.hostOperationsUseStableFieldNumbersAndRoundTrip` |
| 缺操作、错误 UUID、截断/超大消息和提前移除拒绝 | `TechniqueProtocolTest.hostRejectsMissingOperationInvalidUuidAndMalformedPayload` |
| 方向缺省/零/有限值接受，NaN 与无穷拒绝 | `TechniqueProtocolTest.horizontalDirectionAcceptsAbsentZeroAndFiniteValuesButRejectsNonfinite` |
| 损坏或超限进度拒绝且不覆盖身体 | `TechniqueProtocolTest.corruptStateAndOversizeAnyAreRejectedWithoutOverwritingBody` |
| 生成描述集包含框架协议，无正式功法资源 | `TechniqueProtocolTest.generatedDescriptorContainsImportedProtocolAndNoFormalContent` |

补充方案逐项落实：

| 补充要求 | 当前实现 |
|---|---|
| `TechniqueHostRequest` 与校验 | `inspect=10`、`activity=11`、`effect=12`，有界编解码与结构校验；没有宿主发现或执行器 |
| `direction` 与 `INPUT` | 请求保存有限水平 x/z；缺省或零向量语义记入契约；算子枚举含 `INPUT`；不执行方向或速度 |
| 玩家失败文本 | `CastOutcome.failure` 保留结构化原因码，`reason` 只描述身体感受；Framework 拒绝额外保留 `framework_failure` |
| 库不持有身体 | `TechniqueHost.inspect/findActivity/submit`；状态机只有目录字段，测试通过内存假宿主集成真实 Framework |
| `presentation` 走宿主 effect | 保留定义和 `EffectIntent` 的播放/开始/停止数据及校验；不分发，不提供正式表现内容 |
| 预输入、展示投影不做 | 无对应消息、队列或接口；忙碌和冷却直接拒绝 |

KISS/DRY/YAGNI 的落点是复用 Framework 库存算术、有效能力、原子活动和持久回执；
本库只维护声明式定义和必要编排。宿主接口隔离身体所有权，绑定、目录、存档和施放各自承担单一职责。

实际运行记录（2026-10-27）：

| 命令或检查 | 结果 |
|---|---|
| `./gradlew :server:compileJava --console=plain` | 编译通过 |
| `./gradlew :server:test --console=plain` | 39 项测试通过，0 失败、0 错误、0 跳过 |
| `./gradlew test build --console=plain` | 最终构建通过，包含共享协议、服务端测试与打包 |
| 测试 XML 汇总 | 39 项，0 失败、0 错误、0 跳过：目录 8、绑定 3、施放 23、协议 5 |
| `python3 "scripts/check_docs.py"` | 7 个页面链接全部通过；构建的 `checkDocs` 也通过 |
| `git diff --check` | 通过 |

本次补充了真元分配方式、平摊余数和玩家文本回归；没有修改 Framework。

代码形状自查：本次 diff 修正第 4 条（`TechniqueDefinitions`、`TechniqueBinder`、`TechniqueCasting` 与费用测试保存重复读取结果），
并按第 9 条拆开新增测试和校验的超长表达式。新增 builder 每行一个 setter；其余逐条检查无嵌套 builder、复杂三元、自有布尔参数或超长方法。

未检查的内容：游戏内行为、生产宿主授权/持久存储、Fabric 发现与网络、真实实体朝向/冲量/表现、
阶段 3–5、AI、性能基准、Windows 实机执行和正式内容平衡。当前阶段没有 Fabric 工程，不制作无运行职责的空模组。
Framework 的现有接口限制及宿主责任见 [宿主与施放流程](Host-Integration.md)。
