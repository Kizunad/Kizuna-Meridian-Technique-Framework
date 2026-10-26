# 第三方说明

PolyForm Noncommercial 1.0.0 仅适用于本项目有权许可的原创部分。

| 组件 | 版本 / 来源 | 许可与使用 |
|---|---|---|
| Meridian Framework | 相邻独立仓库 | PolyForm Noncommercial 1.0.0；通过 composite build 引用 |
| Gradle Wrapper | 8.8，复制自 Qi-Variants Addon | Apache License 2.0；保留启动脚本版权头、JAR 内许可和分发校验和 |
| Protocol Buffers | 4.36.2 | BSD 3-Clause；构建时使用 protoc，运行时外部解析 protobuf-java |
| JUnit / Hamcrest Core | 4.13.2 / 1.3 | Eclipse Public License 1.0 / BSD 3-Clause；仅测试使用 |

JAR 不内嵌 Framework、Protobuf、Minecraft 或 Fabric。宿主分发时须保留各依赖的原始许可。
