# 功法框架

本库定义通用功法契约、加载校验、标签绑定、施放状态机、效果调度、AI 选择和服务端入口，
并支持阶段 6a 的书卷、持物选手和移动限速，以及 6b 的带时刻打击/劈门意图、承接和战斗情境 AI。
Java 目标为 17，Fabric 宿主目标为 Minecraft 1.20.1；身体与世界效果仍由宿主附属提供。

| 目录 | 职责 |
|---|---|
| `shared/src/main/proto/` | 目录、定义、绑定、进度、施放、宿主请求与世界效果意图协议 |
| `server/src/main/java/` | 不可变目录、定义校验、槽位绑定、施放状态机、效果意图调度和 AI 选择 |
| `server/src/test/java/` | 必要整体回归、内存假宿主、仅用于测试的功法与身体构型 |
| `fabric/src/main/java/` | 目录与宿主发现、主线程调度、学会命令、卷轴物品、创造栏、客户端模型和阅读钩子 |
| `gradle/wrapper/` | Gradle 8.14.6，保留原始许可和下载校验和 |
| `docs/wiki/` | 文档唯一编辑源 |
| `scripts/check_docs.py` | README 与 Wiki 本地链接检查 |

只通过 Gradle composite 引用相邻 `../Kizuna-Meridian-Mod-Framework`。
`settings.gradle` 应用其 `scripts/workspace.gradle`，按装配清单组合框架并取得版本目录 `libs`；
位置可用 `-Pkizuna.dir.framework=...` 指定。只使用本地 composite 的占位依赖，不声明已发布 Maven 安装坐标。
`shared` 编译时导入框架客户端协议，运行时由宿主提供对应端框架；`server` 链接框架纯 Java 服务端。
不依赖 Human、Humanoid、寻路或具体功法内容。

构建与检查：

```sh
./gradlew test build
./gradlew :fabric:runGametest
python3 "scripts/check_docs.py"
git diff --check
```

产物为 `shared/build/libs/`、`server/build/libs/` 和 `fabric/build/libs/` 下的 Java 库、Fabric 模组及源码 JAR。
共享 JAR 内含 `.proto` 和带导入信息的 `META-INF/kizuna_meridian/protocol/technique-v1.desc`。
不手改生成消息，也不复制框架生成类。

`TechniqueService` 通过 `TechniqueHostDirectory` 发现唯一宿主，按服务端 tick 分发 `EffectIntent`，并用 `BodyActivity` 写入学会记录。
宿主通过 `TechniqueFabric.service()` 获取服务，内容目录通过 `custom["meridian_technique:catalogs"]`
声明 `.txtpb` / `.binpb` 清单，也保留 `meridian_technique_catalog` entrypoint 供代码装配；所有来源整体校验。
Fabric 模组注册 `/meridian_technique learn <targets> <id>`（权限 2）和 `KNOWN_CHANGED` 事件；实体宿主经 `meridian_technique_host` entrypoint 提供。
ID 支持卷或单式；整卷原子学习并检查同书前置，已学记录仍按单式精确版本保存。
初始化期可用的每卷自动生成卷轴，独立「功法」创造栏按书卷列出。右键由服务端确认学习，
生存成功消耗 1 个，创造成功不消耗，失败不消耗；缺专用图标时使用内置通用卷轴。
学习成功后客户端自动打开只读卷轴界面，展示书名·卷名、各式名称与说明、前置卷；缺专属背景时使用库内通用纸面。
冷却从收势结束或实际提前终止时刻起算。宿主 inspect 必须同时提供身体及双手物品事实。
承接按可信最近施放与身体时钟判定，原子替换动作，保留前式正常冷却与完整曲线。
持物规则判定出手手位并随意图交给宿主；实际选目标、伤害、劈门和耐久消耗由宿主执行。
预输入、展示投影、熟练度成长和注元伤害留待后续；功法服务把世界效果交给宿主执行。
Fabric 的 `world` 工具包提供共享选目标、持物打击、劈门与正交冲量执行器，由宿主主动调用并提供权限、阵营和做功接线。
正式源码和资源不包含任何功法或身体模板。

- [数据契约与校验](Contracts.md)
- [宿主与施放流程](Host-Integration.md)
- [规则、测试与交付记录](Verification.md)
- [许可](Licensing.md)
