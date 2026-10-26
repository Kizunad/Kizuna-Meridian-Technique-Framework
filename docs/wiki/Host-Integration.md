# 宿主与施放流程

`TechniqueCasting` 只持有不可变 `TechniqueRegistry`。
身体、时钟、释放账户和持久回执都归实现 `TechniqueHost` 的宿主；本库不保留身体缓存或独立账本。
`server/src/test/java/.../MemoryHost.java` 是内存假宿主，底层调用真实 Framework 的 `handleActivity`。
没有生产 Fabric 宿主、玩家通道、学会服务或世界效果执行器。

| 宿主方法 | 必须履行的行为 |
|---|---|
| `inspect()` | 先按旧动作结算至可信当前身体时刻，再返回不可变权威快照 |
| `findActivity(requestId)` | 重新验证授权，查同一身体、`TechniqueHost.CALLER_ID` 下 Framework 持久活动回执 |
| `submit(Submission)` | 检查 `expectedRevision`，走 `handleActivity`，原子保存身体、回执及释放账户；返回实际活动和 revision |

所有调用必须与该身体的其他事件串行。拒绝抛 `TechniqueErrors.HostRejected`，包含原始 Framework 失败码；
不确定的存储结果由调用方用原 `cast_id` 重试，不允许换 ID 再扣一次。
身份和身体归属必须由宿主认证，不能从客户端请求中的身体 ID 推导权限。
宿主为库活动授权的固定调用身份为 `meridian_technique:caster`；这不是绕过玩家授权的通行证。

起手流程按以下顺序进行：

1. 校验 `CastRequest`。客户端不能上传费用、效果剂量或知识记录。
2. 按 `technique_start_<cast_id>` 查持久回执；相同请求返回原起手结果并标记 `replayed`，不同请求返回冲突。
3. 宿主结算时间并提供快照。检查存活、精确功法版本、可信 `known`、无其他施放、冷却结束，再重新绑定。
4. 按绑定经脉的属性/相态扣除内腔费用，释放实际扣除的真元；不能扣外壁、未开拓经脉或其他属性余额。
5. 生成一份 `BodyActivity`，包括 `accounts`、`released_qi`、`efforts`、`start_actions`、`progress`。
   组织和器官占用取并集。经脉不因施放自动接管自然扩散。
6. 预检力量、完整做功区间与原子活动，再交给宿主提交。成功提交只增加一次身体 revision。

冷却从起手时刻开始；所有费用起手一次付清，熟练度不改变数值。
Framework 负责复核 `旧总量 = 新总量 + 释放`、余额、容量、功率、耐力和动作有效性。
库只构造计划，不负责把默认释放账户再转入某个世界区域。

`advance(host)` 根据保存的身体时钟边界返回 `WARMUP`、`EFFECTIVE`、`RECOVERING`。
收势结束再提交一份活动，停止本次动作、清 `active_cast`，保留知识和冷却。
没有逐毫秒循环，也不会因查询阶段状态修改资源或提交效果。
本次没有效果分发，因此声明的效果不会在阶段 1–2 中实际生效。

`interruptByDamage(host)` 只接受可信伤害事件。起手窗口是半开区间 `[start, warmup_end)`，
只有该窗口内且声明可打断时停止。起手结束边界上的伤害不取消施放。
`cancelOnUnload`、`cancelOnDisconnect` 清当前施放与动作，不退任何费用，不清冷却。
宿主必须在冻结、撤销访问权或销毁实体之前完成停止提交；失败须处理或重试。
多次查询到空施放返回 `IDLE`。

所有状态保存在 `addon_progress["meridian_technique:state"]` 的 `TechniqueProgress`：

- `known`：精确功法版本、熟练度、可信来源与学会身体时刻；本次不提供学会命令。
- `cooldowns`：功法 ID 到冷却结束身体毫秒；跨功法版本共用冷却。
- `active_cast`：原始请求、三段固定边界、动作身份、绑定和打断规则；至多一份。

损坏、类型不符、未来版本或超限进度拒绝，不按空进度覆盖。
快照和 Framework 回执必须一起保存/恢复；只恢复快照会丢失已结束施放的去重能力。
历史重试返回原起手结果，当前实际状态应另调 `advance` 检查。
同一 ID 的方向或目标变化也视为不同请求。
收势不再查目录，所以内容卸载后仍能解除已保存动作。

Framework 现有接口限制已记录，未修改依赖：

- 活动做功没有力量字段。本库提交前用 `TissueCapabilities.exert` 校验同一 revision 的力量和完整时段，
  提交时复核 revision；Framework 回执只携带功率/秒，不独立保留力量要求。
- Framework 没有全局标签目录或模板枚举；本库使用显式模板引用校验词汇。
- Framework 死亡会清动作并保留附属进度，但拒绝死体 `BodyActivity`。
  若宿主先让身体死亡，再尝试收势，库会明确报告 `FRAMEWORK_REJECTED/BODY_DEAD`，保留原进度供宿主处理。
  死亡后的 `active_cast` 清理需宿主使用现有可信进度入口，或在死亡前按事件顺序完成取消；本次不增加此宿主能力。

阶段 5 才实现 `meridian_technique_host` 发现、没有认领时的 `NO_HOST`、服务端调度与 `TechniqueService`。
当前宿主消息只定义和校验；纯 Java 的 `Submission` 显式携带 revision，未来字节适配也必须保证同一事件串行与版本安全。
`presentation` 届时通过 `effect` 交由宿主执行，持续型需要结束通知；首版测试和正式内容均不依赖表现。
