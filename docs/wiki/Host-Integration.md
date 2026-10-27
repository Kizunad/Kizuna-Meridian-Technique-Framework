# 宿主与施放流程

`TechniqueCasting` 只持有不可变 `TechniqueRegistry`。
身体、时钟、释放账户和持久回执都归实现 `TechniqueHost` 的宿主；本库不保留身体缓存或独立账本。
`server/src/test/java/.../MemoryHost.java` 是内存假宿主，底层调用真实 Framework 的 `handleActivity`。
`TechniqueHostDirectory` 通过 Fabric 的 `meridian_technique_host` entrypoint 找到唯一认领者；
Fabric 装配纯 Java `TechniqueService`，负责主线程 tick、学会命令和效果转发。

内容附属通过 `meridian_technique_catalog` entrypoint 实现 `TechniqueRegistryEntrypoint`，提供已校验目录；
只能有一个目录提供者，不提供静态全局安装入口。测试目录与宿主仅在 GameTest 源集声明。
宿主和战斗 UI 在服务器启动后通过唯一公开入口 `TechniqueFabric.service()` 取得当前服务；
服务器尚未启动、已停止或没有目录时抛出 `IllegalStateException`，不返回 `null`。
服务只在服务端主线程使用，网络请求须先切回该线程，客户端不能直接调用。

宿主应在可信受伤事件确认发生后、身体仍可提交活动时调用：

```java
TechniqueService service = TechniqueFabric.service();
service.interruptByDamage(entity.getUuid());
```

施放与查询同样调用 `service.cast(entity.getUuid(), request)`、`service.known(entity.getUuid())`；
目录使用 `service.registry()`，与命令、状态机共享启动时的同一份不可变目录。

| 宿主方法 | 必须履行的行为 |
|---|---|
| `inspect()` | 先按旧动作结算至可信当前身体时刻，再返回不可变权威快照 |
| `findActivity(requestId)` | 重新验证授权，查同一身体、`TechniqueHost.CALLER_ID` 下 Framework 持久活动回执 |
| `submit(Submission)` | 检查 `expectedRevision`，走 `handleActivity`，原子保存身体、回执及释放账户；返回实际活动和 revision |
| `submitEffect(EffectSubmission)` | 校验 `EffectIntent`，把冲量或表现交给实体宿主；本库不直接改世界 |

所有调用必须与该身体的其他事件串行。拒绝抛 `TechniqueErrors.HostRejected`，包含原始 Framework 失败码；
不确定的存储结果由调用方用原 `cast_id` 重试，不允许换 ID 再扣一次。
身份和身体归属必须由宿主认证，不能从客户端请求中的身体 ID 推导权限。
宿主为库活动授权的固定调用身份为 `meridian_technique:caster`；这不是绕过玩家授权的通行证。

起手流程按以下顺序进行：

1. 校验 `CastRequest`。客户端不能上传费用、效果剂量或知识记录。
2. 按 `technique_start_<cast_id>` 查持久回执；相同请求返回原起手结果并标记 `replayed`，不同请求返回冲突。
3. 宿主结算时间并提供快照。检查存活、精确功法版本、可信 `known`、无其他施放、冷却结束，再重新绑定。
4. 按每项 `QiCost.distribution` 扣除绑定经脉的内腔费用：`EACH` 每条经脉扣 `amount`，`EVEN` 在全部绑定经脉间平摊总 `amount`；释放实际扣除的真元，不能扣外壁、未开拓经脉或其他属性余额。
5. 生成一份 `BodyActivity`，包括 `accounts`、`released_qi`、`efforts`、`start_actions`、`add_modifiers`、`progress`。
   组织和器官占用取并集。经脉不因施放自动接管自然扩散；新增修正和扣费同一 revision 原子生效。
6. 预检力量、完整做功区间与原子活动，再交给宿主提交。成功提交只增加一次身体 revision。

冷却从起手时刻开始；所有费用起手一次付清，熟练度不改变数值。
Framework 负责复核 `旧总量 = 新总量 + 释放`、余额、容量、功率、耐力和动作有效性。
库只构造计划，不负责把默认释放账户再转入某个世界区域。
`EVEN` 的每条经脉余额单独预检，任一不足就整笔拒绝；扣费后的不平衡由 Framework 的 `PassiveQiDiffusion` 自然回流，本库不再分配。

`advance(host)` 根据保存的身体时钟边界返回 `WARMUP`、`EFFECTIVE`、`RECOVERING`。
收势结束再提交一份活动，停止本次动作、清 `active_cast`，保留知识和冷却。
没有逐毫秒循环，也不会因查询阶段状态修改资源或执行世界变化。
需要效果意图时，宿主用 `advance(host, previousMillis)`；返回的 `AdvanceResult.effects` 是区间内到期的冲量/表现，
`recoveryCommit` 是收势或取消活动的可信终止回执。组织修正不走效果意图，而是在起手活动里随曲线提交。
`advancedMillis` 是该结果实际读取并调度到的身体时刻；成功分发后用它更新游标，不能另行 inspect 后猜测。
失败结果保留原 `previousMillis`，不能据此跳过效果。

`interruptByDamage(host, previousMillis)` 只接受可信伤害事件。起手窗口是半开区间 `[start, warmup_end)`，
只有该窗口内且声明可打断时停止。起手结束边界上的伤害不取消施放。
`cancelOnUnload(host, previousMillis)`、`cancelOnDisconnect(host, previousMillis)` 清当前施放与动作，
不退任何费用，不清冷却。三个提前结束入口都返回 `AdvanceResult`；若 `previousMillis` 已越过持续表现的
达到生效时刻，则同时返回对应 `PresentationAction.STOP`，否则不伪造停止意图。
宿主必须在冻结、撤销访问权或销毁实体之前完成停止提交；失败须处理或重试。
多次查询到空施放返回 `IDLE`。

`TechniqueService.cast(entity, request)` 先解析唯一宿主，再调用现有状态机；无人认领返回 `NO_HOST`，多个认领抛出接线错误。
`known(entity)` 读取可信 `TechniqueProgress`，返回精确功法 ID 和目录名称。`learn(entity, technique)` 生成只含
`progress` 的 `BodyActivity`，使用身体 revision 原子提交；成功后发布 `KNOWN_CHANGED`，重复学会保持幂等。
Fabric 模组注册 `/meridian_technique learn <targets> <id>`，只允许权限 2；命令直接使用服务持有的目录。
每 tick 只推进已成功起手的实体，正常推进和收势共用一次 `advance`，效果请求 ID 由施放 ID、事件游标和序号组成。
服务保留整份待确认结果，全部效果收到回执才更新到 `advancedMillis`。收势活动可能已经提交，效果暂时拒绝时
仍重试原批次，不重新读取空施放后丢掉意图；部分已成功的效果由回执去重。重复 `cast` 不重置现有游标。

`REVISION_CONFLICT`、`TIME_REQUIRES_SETTLEMENT`、`PENDING_TIME_EVENT`、`ENVIRONMENT_UNAVAILABLE`、
`ENVIRONMENT_REVISION_CONFLICT` 和 `INTERNAL_ERROR` 保留会话供下一 tick 重试。
`UNBOUND` 映射为 `BODY_NOT_FOUND`，与其他永久拒绝、损坏状态一起移除会话，避免实体消失后无限重试。
清理会话只释放服务端引用，不伪造身体活动成功；身体进度仍由原宿主处理。

`ServerEntityEvents.ENTITY_UNLOAD` 对非玩家实体调用 `cancelOnUnload`；玩家断线由
`ServerPlayConnectionEvents.DISCONNECT` 调用 `cancelOnDisconnect`，避免同次下线重复处理。
服务器停止前取消剩余施放。实体宿主必须让这些回调先于撤销身体访问执行，已支付费用不退回。

所有状态保存在 `addon_progress["meridian_technique:state"]` 的 `TechniqueProgress`：

- `known`：精确功法版本、熟练度、可信来源与学会身体时刻；管理命令是当前学会入口。
- `cooldowns`：功法 ID 到冷却结束身体毫秒；跨功法版本共用冷却。
- `active_cast`：原始请求、三段固定边界、动作身份、绑定和打断规则；至多一份。

损坏、类型不符、未来版本或超限进度拒绝，不按空进度覆盖。
快照和 Framework 回执必须一起保存/恢复；只恢复快照会丢失已结束施放的去重能力。
历史重试返回原起手结果，当前实际状态应另调 `advance` 检查。
同一 ID 的方向或目标变化也视为不同请求。
收势不再查目录，所以内容卸载后仍能解除已保存动作。

效果游标和待确认结果只保存在服务器内存，不跨重启恢复，也不补发离线期间的冲量或表现。
正常卸载、下线和停服会先取消当前施放；异常退出遗留的 `active_cast` 必须由宿主在恢复身体时、
重新对外认领之前，调用 `TechniqueService.recoverOnLoad(entityUuid, recoveryHost)` 清理。
`TechniqueRecoveryHost` 不依赖实体目录已认领：`snapshot()` 返回权威事实，`submitRecovery` 将库生成的
`BodyCommand` 按类型交给 Framework 的 `handleActivity` 或 `handleAddonProgress`，保留原始请求 ID、
`expected_revision` 和失败码，并返回实际提交版本。调用与身体事件串行，恢复前由宿主结清时间。
存活身体通过活动同时清施放与动作；死体动作已被 Framework 清空，因此只通过可信进度入口清 `active_cast`。
成功后清除内存旧会话；拒绝不当作成功，可重试。恢复不补发冲量、START 或 STOP；知识、冷却与已付费用保持不变。
服务不枚举存档身体，不恢复旧施放或重建效果游标；损坏进度仍明确拒绝。

Framework 现有接口限制已记录，未修改依赖：

- 活动做功没有力量字段。本库提交前用 `TissueCapabilities.exert` 校验同一 revision 的力量和完整时段，
  提交时复核 revision；Framework 回执只携带功率/秒，不独立保留力量要求。
- Framework 没有全局标签目录或模板枚举；本库使用显式模板引用校验词汇。
- Framework 死亡会清动作并保留附属进度，但拒绝死体 `BodyActivity`。
  宿主优先在死亡前取消；身体已致死或恢复旧死体时，使用上述 `recoverOnLoad` 走可信进度清理。
  此入口不复活身体，也不补回死亡释放的资源。

字节适配的 `TechniqueHostRequest.expected_revision` 显式携带活动提交依据的版本，宿主必须在同一实体事件队列中复核。
`presentation` 通过 `effect` 交由宿主执行，持续型在收势、打断、卸载或下线时收到停止通知；
GameTest 覆盖表现 START/STOP 和冲量下发，正式源码不提供任何表现内容。
