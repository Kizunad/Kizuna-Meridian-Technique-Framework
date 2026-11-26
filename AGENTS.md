# 仓库协作约定

- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。遵守 `~/.codex/AGENTS.md` 的 Java 代码形状规则。
- `shared/` 是独立 Protobuf 契约；`server/` 是 Java 25 纯 Java 库，面向 Minecraft 26.2 宿主。
- 只依赖相邻 Meridian Framework，不依赖 Human、Humanoid、寻路库或具体内容附属。
- 正式源码不提供任何具体功法或身体模板；测试数据只放在测试源码集。
- 身体、真元、做功、动作与附属进度只有 Framework 的权威状态；本库通过 `handleActivity` 提交。
- 本库状态放在 `addon_progress["meridian_technique:state"]`；可信学会记录也走 Framework 的进度入口。
- 一个身体同时施放一个功法；费用在起手一次付清，打断或卸载不退费；冷却使用身体时钟。
- 旧 `tissue_modifier` 必须跑完整条曲线，普通功法活动不得提前移除。状态宿主只能通过受限状态活动撤销
  `meridian_technique:status/` 下自己登记且实际存在的修正；不得清理历史 `cast/` 曲线。
- 文档唯一编辑源是 `docs/wiki/`，README 仅作入口；契约变更同步更新 Wiki 和必要的整体回归测试。
- 修改前读相邻代码；用 patch/Edit 修改；保留用户改动，不修改依赖仓库。
- 每个方法、构造器内部写中文意图注释；一行不超过 120 列，不嵌套 builder，不用复杂三元。
- 自有方法不使用布尔参数，参数超过 5 个用 record；方法超过约 40 行按完整业务阶段拆分。
- 整体测试只保留公开契约、关键流程、模块交互与已知缺陷保护；临时探针验证后清理。
- 交付运行 `python3 "scripts/check_docs.py"` 和 `git diff --check`，汇报代码形状自查。
- 提交、推送、远端建仓分别按用户授权执行，不强推、不重写历史。
- 原创部分采用 PolyForm Noncommercial 1.0.0，保留第三方许可。

## 构建和测试

构建、测试一律走工作区的 `kz`（`~/Code/Kizuna-remote-session/scripts/kz`），不直接运行 `./gradlew`。细则以 `~/Code/Kizuna-remote-session/handoff/codex-worker-rules.md` 为准，动手前先读。

- 单测、构建和服务端 GameTest：`kz test ~/Code/<本仓的 worktree>`
- 要带上别的仓的 worktree 一起测：每个加一次 `--with ~/Code/<那个仓的 worktree>`
- 客户端测试：`kz client ~/Code/<本仓的 worktree>` 列出有哪些项，`kz client ~/Code/<本仓的 worktree> <项>` 跑其中一项
- 要自己写 Gradle 参数时：`kz gradle ~/Code/<本仓的 worktree> -- <参数>`
- 不提交、不推送；在自己的 worktree（`~/Code/wt-*`）里改，不改主目录。

## 速查

### 自动生成

- Fabric 模组 `meridian_technique`，清单 `fabric/src/main/resources/fabric.mod.json`
  - `client` → `dev.kizuna.meridian.technique.fabric.client.TechniqueScrollClient`
  - `main` → `dev.kizuna.meridian.technique.fabric.TechniqueFabric`
- 数据目录：`fabric/src/gametest/resources/data/meridian_technique_test/`、`fabric/src/gametest/resources/data/sample_manifest/`、`fabric/src/gametest/resources/data/sample_scrolls/`、`fabric/src/main/resources/data/meridian_technique/`
- 资源目录：`fabric/src/gametest/resources/assets/sample_scrolls/`、`fabric/src/main/resources/assets/meridian_technique/`
- Protobuf 数据文件（含功法目录）：`fabric/src/gametest/resources/data/sample_manifest/catalog.txtpb`、`fabric/src/gametest/resources/data/sample_scrolls/catalog.txtpb`、`server/src/test/resources/peddler_leg_strikes.txtpb`
- 随包 TOML：无
- 测试源集：`fabric/src/clientTest/`、`fabric/src/gametest/`、`fabric/src/test/`、`fabric/src/testFixtures/`、`server/src/test/`
- `kz` 装配 ID：`technique`（`--with` 使用工作树路径）
- `kz test --part unit`：`test build`
- `kz test --part gametest`
  - `:fabric:runGametest -PgametestRunDir=build/{name}`，运行目录 `fabric/build/{name}`
- `kz client` 项目：`leap`、`scroll`、`status`

### 常见坑

- 2026-11-22 新增 GameTest 类还要登记测试源集的 `fabric-gametest` 入口。交付前逐名核日志和总条数。来源 `codex-worker-rules.md`「新用例要真跑到」。
- 2026-11-10 改默认配置后用 `kz test <树> --part gametest` 起新运行目录，旧配置文件会保留原值。来源 `C5b-FIX-TASK.md`。
- 2026-11-25 迭代时用 `--part gametest -- -PgametestFilter=<类或方法>`，用例通过后再跑全量和负对照。来源 `KNOWN-PUSH-FIX-20261125-TASK.md` 和 dev 构建规则。
- 2026-11-26 JUnit 若触发 Minecraft 日志初始化，将工作目录设在模块的 `build/test-runtime/`，检查树里有无新增日志。来源 `TEST-LOGS-OUT-TASK.md`。
