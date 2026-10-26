# 仓库协作约定

- 简体中文沟通、文档与函数内意图注释；公开标识符用英文。遵守 `~/.codex/AGENTS.md` 的 Java 代码形状规则。
- `shared/` 是独立 Protobuf 契约；`server/` 是 Java 17 纯 Java 库，面向 Minecraft 1.20.1 宿主。
- 只依赖相邻 Meridian Framework，不依赖 Human、Humanoid、寻路库或具体内容附属。
- 正式源码不提供任何具体功法或身体模板；测试数据只放在测试源码集。
- 身体、真元、做功、动作与附属进度只有 Framework 的权威状态；本库通过 `handleActivity` 提交。
- 本库状态放在 `addon_progress["meridian_technique:state"]`；可信学会记录也走 Framework 的进度入口。
- 一个身体同时施放一个功法；费用在起手一次付清，打断或卸载不退费；冷却使用身体时钟。
- 本库修正必须跑完整条曲线，不生成 `remove_modifiers`；效果分发和 AI 选择器尚未实现。
- 文档唯一编辑源是 `docs/wiki/`，README 仅作入口；契约变更同步更新 Wiki 和必要的整体回归测试。
- 修改前读相邻代码；用 patch/Edit 修改；保留用户改动，不修改依赖仓库。
- 每个方法、构造器内部写中文意图注释；一行不超过 120 列，不嵌套 builder，不用复杂三元。
- 自有方法不使用布尔参数，参数超过 5 个用 record；方法超过约 40 行按完整业务阶段拆分。
- 整体测试只保留公开契约、关键流程、模块交互与已知缺陷保护；临时探针验证后清理。
- 交付运行 `./gradlew test build`、`python3 "scripts/check_docs.py"` 和 `git diff --check`，汇报代码形状自查。
- 提交、推送、远端建仓分别按用户授权执行，不强推、不重写历史。
- 原创部分采用 PolyForm Noncommercial 1.0.0，保留第三方许可。
