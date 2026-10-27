# 功法框架

本库只定义通用功法契约、加载校验、标签绑定、施放状态机、效果调度、AI 选择和阶段 5 服务端入口。
Java 目标为 17，Fabric 宿主目标为 Minecraft 1.20.1；身体与世界效果仍由宿主附属提供。

| 目录 | 职责 |
|---|---|
| `shared/src/main/proto/` | 目录、定义、绑定、进度、施放、宿主请求与世界效果意图协议 |
| `server/src/main/java/` | 不可变目录、定义校验、槽位绑定、施放状态机、效果意图调度和 AI 选择 |
| `server/src/test/java/` | 必要整体回归、内存假宿主、仅用于测试的功法与身体构型 |
| `fabric/src/main/java/` | Fabric 主线程调度、宿主 entrypoint 发现、学会命令和 `KNOWN_CHANGED` 事件 |
| `gradle/wrapper/` | Gradle 8.8，保留原始许可和下载校验和 |
| `docs/wiki/` | 文档唯一编辑源 |
| `scripts/check_docs.py` | README 与 Wiki 本地链接检查 |

只通过 Gradle composite 引用相邻 `../Kizuna-Meridian-Mod-Framework`。
依赖位置可用 `-PmeridianFrameworkDir=...` 指定。只使用本地 composite 的占位依赖，不声明已发布 Maven 安装坐标。
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
宿主通过 `TechniqueFabric.service()` 获取服务，内容目录只通过 `meridian_technique_catalog` entrypoint 提供。
Fabric 模组注册 `/meridian_technique learn <targets> <id>`（权限 2）和 `KNOWN_CHANGED` 事件；实体宿主经 `meridian_technique_host` entrypoint 提供。
预输入、展示投影、熟练度成长和注元伤害留待后续；本库只把世界效果交给宿主执行，不直接改世界。
正式源码和资源不包含任何功法或身体模板。

- [数据契约与校验](Contracts.md)
- [宿主与施放流程](Host-Integration.md)
- [规则、测试与交付记录](Verification.md)
- [许可](Licensing.md)
