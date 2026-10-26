# 功法框架

本库只定义通用功法契约、加载校验、标签绑定和施放状态机。当前实现阶段 1–2，未发布。
Java 目标为 17，宿主目标为 Minecraft 1.20.1；当前库不引用 Minecraft 或 Fabric 类型。

| 目录 | 职责 |
|---|---|
| `shared/src/main/proto/` | 目录、定义、绑定、进度、施放、宿主请求与世界效果意图协议 |
| `server/src/main/java/` | 不可变目录、定义校验、槽位绑定、通过宿主提交的施放状态机 |
| `server/src/test/java/` | 必要整体回归、内存假宿主、仅用于测试的功法与身体构型 |
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
python3 "scripts/check_docs.py"
git diff --check
```

产物为 `shared/build/libs/`、`server/build/libs/` 下的 Java 库及源码 JAR。
共享 JAR 内含 `.proto` 和带导入信息的 `META-INF/kizuna_meridian/protocol/technique-v1.desc`。
不手改生成消息，也不复制框架生成类。

Fabric 工程、效果分发（阶段 3）、AI 选择器（阶段 4）、`TechniqueService`、实体宿主发现、学会命令和
`KNOWN_CHANGED`（阶段 5）均未实现。预输入、展示投影、熟练度成长和注元伤害留待后续。
正式源码和资源不包含任何功法或身体模板。

- [数据契约与校验](Contracts.md)
- [宿主与施放流程](Host-Integration.md)
- [规则、测试与交付记录](Verification.md)
- [许可](Licensing.md)
