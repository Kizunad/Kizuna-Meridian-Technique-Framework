# 加一卷类人功法

先定书、卷、式的资源 ID，以及本卷的前置卷、适用物种、出生档位、来源和对应动作。以下路径按所属仓库根目录写，尖括号内填本卷的名字。先例链接指向现有文件及对应行，`scripts/check_docs.py` 在相邻仓中检查这些路径。

## 1. 登记书、卷、式

在附属的 `shared/src/main/resources/data/<命名空间>/technique_catalog.txtpb` 增补目录。故事仓把目录随 Fabric 模块打包，用相同的资源路径声明。先写根级 `schema_version` 和 `namespace`，类人绑定填 `binding_template_groups: "human:humanoid"`，再接三层。

| 段 | 需要填写 |
|---|---|
| `definitions` | 式的 `id`、`version`、`category`、`minimum_realm`，身体槽 `slots` 和起手 `cost`，`phases`、`cooldown_millis`、各拍 `effects.at_millis`、`presentation`。 |
| `books` | 书的 `id`、`display_name` 和适用的 `held_item_rules`。同一本书沿用同一书 ID。 |
| `volumes` | 卷的 `id`、`book_id`、`display_name` 和 `techniques { id: … version: … }`。有同书前置就填 `prerequisite_volume_ids`。 |

在附属 `fabric/src/main/resources/fabric.mod.json` 的 `custom["meridian_technique:catalogs"]` 填打包后的 `data/<命名空间>/technique_catalog.txtpb`。书卷引用的版本要与式定义一致。复制云溪上卷的书卷及一式即可起步，后面的破门斩示例演示多拍与位移。[故事仓书和卷，第 5–44 行](https://github.com/Kizunad/Kizuna-Meridian-Story-Addon/blob/main/fabric/src/main/resources/data/meridian_story/technique_catalog.txtpb#L5-L44) · [故事仓 `wind_thrust`，第 46–105 行](https://github.com/Kizunad/Kizuna-Meridian-Story-Addon/blob/main/fabric/src/main/resources/data/meridian_story/technique_catalog.txtpb#L46-L105) · [破门斩多拍 `knock`，第 120–289 行](https://github.com/Kizunad/Kizuna-Meridian-Door-Breaker-Addon/blob/main/shared/src/main/resources/data/meridian_door_breaker/technique_catalog.txtpb#L120-L289) · [位移加打击 `ram`，第 399–504 行](https://github.com/Kizunad/Kizuna-Meridian-Door-Breaker-Addon/blob/main/shared/src/main/resources/data/meridian_door_breaker/technique_catalog.txtpb#L399-L504) · [流金剑清单，第 16–18 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/fabric.mod.json#L16-L18)

持矛的目录规则可填 `required_item_tags: "minecraft:spears"`。直接核过 Minecraft 26.2 原版 JAR 中的 `data/minecraft/tags/item/spears.json`，它包含木、石、铜、铁、金、钻石、下界合金七种矛。目录字段写资源 ID，物品标签 JSON 的 `values` 如果要引用整个标签则写 `"#minecraft:spears"`。

## 2. 接主、副手伤害类型

在附属 `fabric/src/main/resources/data/<命名空间>/damage_type/` 增加 `<式>_main_hand.json` 和 `<式>_off_hand.json`，照先例填写 `message_id`、`exhaustion`、`scaling`。在目录对应 `weapon_strike` 填 `main_hand_damage_type_id`、`off_hand_damage_type_id`，在多个命中拍逐拍填对应的 ID。[流金剑类型主手，第 1–5 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian_piglin_sword/damage_type/wind_around_main_hand.json#L1-L5) · [副手，第 1–5 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian_piglin_sword/damage_type/wind_around_off_hand.json#L1-L5) · [目录打击，第 142–169 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/shared/src/main/resources/data/meridian_piglin_sword/technique_catalog.txtpb#L142-L169)

同一资源根的 `data/meridian/tags/damage_type/` 下，两份类型都登记到 `melee.json` 和适用的 `kind/<伤害类别>.json`。副手类型再加到 `off_hand.json`。指定瞄准分组时两个手别都加进对应 `aim/<分组>.json`。保持标签 `replace: false`，`values` 填完整的伤害类型 ID。[近战，第 1–5 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian/tags/damage_type/melee.json#L1-L5) · [副手，第 1–4 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian/tags/damage_type/off_hand.json#L1-L4) · [切割，第 1–5 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian/tags/damage_type/kind/cutting.json#L1-L5) · [头部瞄准，第 1–7 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian/tags/damage_type/aim/head.json#L1-L7)

## 3. 登记卷轴

正式卷沿用 `scroll_item` 的默认值。框架注册 `<命名空间>:<卷路径>_scroll`，并加入功法创造栏。把同一物品 ID 写进附属随包的 `data/meridian_technique/tags/item/scrolls.json` 的 `values`。给卷轴配 `assets/<命名空间>/textures/item/<卷路径>_scroll.png` 和两种语言的物品名。生存获取在第 9 步定。[功法框架卷轴约定](Contracts.md#卷轴掉落) · [流金剑卷轴标签，第 1–6 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/shared/src/main/resources/data/meridian_technique/tags/item/scrolls.json#L1-L6) · [流金剑上卷物品图](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/assets/meridian_piglin_sword/textures/item/upper_scroll.png)

## 4. 配齐语言键

修改附属 `fabric/src/main/resources/assets/<命名空间>/lang/zh_cn.json` 和同目录的 `en_us.json`。同时补书、卷、式名与说明、卷轴物品名，以及目录实际引用的拒绝提示 `translation_key`。运行 `python3 "$HOME/Code/Kizuna-remote-session/scripts/check-lang-pairs.py" "$HOME/Code/Kizuna-Meridian-Piglin-Sword-Addon"`，替换最后一个参数为当前附属工作树，预期 `language pairs clean: 1 repositories`。[流金剑中文，第 2–15 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/assets/meridian_piglin_sword/lang/zh_cn.json#L2-L15) · [英文，第 2–15 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/assets/meridian_piglin_sword/lang/en_us.json#L2-L15) · [语言检查脚本](../../../Kizuna-remote-session/scripts/check-lang-pairs.py)

## 5. 配式图标

定义的 `presentation.icon_id: "<命名空间>:<式路径>"` 解析到 `assets/<命名空间>/textures/gui/technique/<式路径>.png`。用 CPA 给每式和卷轴出独立图稿，按同仓的像素尺寸和画法处理。原稿和提示词留在任务日志目录，处理后的 PNG 放进附属随包资源。[图标字段，第 170–174 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/shared/src/main/resources/data/meridian_piglin_sword/technique_catalog.txtpb#L170-L174) · [流金剑「绕」图标](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/assets/meridian_piglin_sword/textures/gui/technique/wind_around.png) · [Combat UI 资源解析，第 130–136 行](https://github.com/Kizunad/Kizuna-Meridian-Combat-UI-Addon/blob/master/core/src/main/java/dev/kizuna/meridian/combat/core/CombatHud.java#L130-L136) · [CPA 图片脚本，第 14–25 行](../../../Kizuna-remote-session/handoff/technique-art-20261028-work/cpa_generate.py#L14-L25)

## 6. 对齐动作和手别

目录 `effects { presentation { id: … } }` 的 ID 路径部分就是 PA 剪辑名。按效果的 `at_millis`、`start_millis` 和起收势时刻定动作规格，在 PA 的 `models/player_skeleton_classic/` 对应分组制作关键帧，再导出资源并登记 `player_anim_index.json`。分手别时副手剪辑名在主名后加 `_off`。当前人体宿主按 `HANDED_PRESENTATIONS` 前缀表识别分手别的书，要接新书就在该表加完整命名空间和前缀，F5 完成后随目录字段迁移。[「绕」动作事件，第 134–141 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/shared/src/main/resources/data/meridian_piglin_sword/technique_catalog.txtpb#L134-L141) · [人体手别解析，第 34–49 行](https://github.com/Kizunad/Kizuna-Meridian-Human-Addon/blob/main/server/src/main/java/dev/kizuna/meridian/human/server/HumanTechniqueAnimations.java#L34-L49) · [PA 主手索引，第 6499–6500 行](https://github.com/Kizunad/Kizuna-Players-Animation/blob/main/src/main/resources/assets/kizuna_players_animation/player_anim_index.json#L6499-L6500) · [副手索引，第 6575–6576 行](https://github.com/Kizunad/Kizuna-Players-Animation/blob/main/src/main/resources/assets/kizuna_players_animation/player_anim_index.json#L6575-L6576) · [PA 制作与导出约定](https://github.com/Kizunad/Kizuna-Players-Animation/blob/main/docs/asset-contract.md#L17-L39) · [动作规格](https://github.com/Kizunad/Kizuna-Players-Animation/blob/main/docs/animation-specs/piglin_sword_wind_around.md)

## 7. 核对类人骨架

同物种已支持时直接用现有骨架。新物种在 PA-Mobs 的 `MobGeometryAssets.REGISTRATIONS` 加一条 `Registration(geometryId, path, entityTypes, overlayGeometryId)`。`path` 是相对 `assets/kizuna_players_animation_mobs/` 的资源路径，`geometryId` 取几何 JSON 的 `description.identifier`，`entityTypes` 填完整的实体注册 ID 列表，单层骨架的 `overlayGeometryId` 传空字符串。猪灵、蛮兵、僵尸猪灵共用 `geometry.mob.piglin`。同一注册表还给 `supports` 提供自动动画的物种集合，客户端重载时通过 PA 宿主登记几何。[PA-Mobs 物种登记和重载，第 18–65 行](https://github.com/Kizunad/Kizuna-Players-Animation-Mobs-Addon/blob/main/src/main/java/dev/kizuna/playeranimation/mobs/MobGeometryAssets.java#L18-L65) · [几何资源猪灵段，第 6213 行](https://github.com/Kizunad/Kizuna-Players-Animation-Mobs-Addon/blob/main/src/main/resources/assets/kizuna_players_animation_mobs/geo/mob_skeletons.geo.json#L6213) · [PA 宿主调用，第 29–52 行](https://github.com/Kizunad/Kizuna-Players-Animation-Mobs-Addon/blob/main/src/main/java/dev/kizuna/playeranimation/mobs/MobAnimationClient.java#L29-L52)

## 8. 登记生人出生学卷

附属的 `fabric/src/main/resources/fabric.mod.json` 在 `custom` 里声明单个字符串，例如 `"meridian_humanoid:volumes": "data/meridian_villager/humanoid_volumes.toml"`。加载器从声明该键的模组容器读文件。片段按 `[volumes."minecraft:villager"]` 等物种表书写，各档 `mortal`、`wandering_qi`、`true_qi` 填 `{ volume = "<命名空间>:<卷>", chance = 1.0 }` 条目的数组，前置卷排在后续卷之前。配置里重复声明的物种、档位、卷沿用配置值。拟进入随机外卷池时在生人仓的 `server/src/main/resources/meridian-humanoid-brain.toml` 查 `[volume_pool]` 和 `[volume_pool.books."<书ID>"]`，按书设置 `native`、`weight`、`upper` 和适用时的 `middle`。[袖手拳清单，第 28 行](https://github.com/Kizunad/Kizuna-Meridian-Villager-Fist-Addon/blob/main/fabric/src/main/resources/fabric.mod.json#L28) · [TOML 片段，第 7–17 行](https://github.com/Kizunad/Kizuna-Meridian-Villager-Fist-Addon/blob/main/fabric/src/main/resources/data/meridian_villager/humanoid_volumes.toml#L7-L17) · [生人加载器，第 29–60 行](https://github.com/Kizunad/Kizuna-Meridian-Vanilla-Humanoid-Threatening/blob/master/fabric/src/main/java/dev/kizuna/meridian/humanoid/fabric/HumanoidAddonVolumes.java#L29-L60) · [外卷池，第 100–141 行](https://github.com/Kizunad/Kizuna-Meridian-Vanilla-Humanoid-Threatening/blob/master/server/src/main/resources/meridian-humanoid-brain.toml#L100-L141)

## 9. 按设计接生存来源

击杀掉落在目录卷段添加 `scroll_drops { entity_type: … chance: … }`。交易和战利品走附属 `fabric/src/main/resources/data/<命名空间>/loot_table/` 下的真实表引用，表里的物品条目填卷轴 ID。涉及来犯队伍或出身时，生人仓相应目录是 `fabric/src/main/resources/data/meridian_humanoid/meridian_humanoid/crisis_party/` 和 `fabric/src/main/resources/data/meridian_humanoid/meridian_origin/origin/`。设计指定生存不发放的卷，省略生存来源和随机外卷池登记。[破门斩掉落，第 88–118 行](https://github.com/Kizunad/Kizuna-Meridian-Door-Breaker-Addon/blob/main/shared/src/main/resources/data/meridian_door_breaker/technique_catalog.txtpb#L88-L118) · [流金剑交易表，第 1–7 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian_piglin_sword/loot_table/barter_scroll.json#L1-L7) · [堡垒表，第 1–7 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/fabric/src/main/resources/data/meridian_piglin_sword/loot_table/bastion_scroll.json#L1-L7) · [来犯队伍例](https://github.com/Kizunad/Kizuna-Meridian-Vanilla-Humanoid-Threatening/blob/master/fabric/src/main/resources/data/meridian_humanoid/meridian_humanoid/crisis_party/captors.json#L1) · [出身授卷例，第 4–9 行](https://github.com/Kizunad/Kizuna-Meridian-Vanilla-Humanoid-Threatening/blob/master/fabric/src/main/resources/data/meridian_humanoid/meridian_origin/origin/apprentice.json#L4-L9)

## 10. 登记用例

照流金剑附属在 `gametest/src/main/java/...GameTest.java` 测真宿主的命中、挥空和费用回执，按卷的设计补学卷、可用手别和来源。新增类写进 `gametest/src/main/resources/fabric.mod.json` 的 `entrypoints.fabric-gametest`。客户端表现验收放在 `clienttest/src/main/java/`，在 `clienttest/src/main/resources/fabric.mod.json` 登记 `entrypoints.fabric-client-gametest`，再登记装配清单的运行项，验动作手别、时刻和截图。[流金剑 GameTest 学卷和双手，第 106–163 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/gametest/src/main/java/dev/kizuna/meridian/piglinsword/PiglinSwordGameTest.java#L106-L163) · [挥空，第 295–310 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/gametest/src/main/java/dev/kizuna/meridian/piglinsword/PiglinSwordGameTest.java#L295-L310) · [GameTest 入口，第 10–17 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/gametest/src/main/resources/fabric.mod.json#L10-L17) · [客户端用例](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/clienttest/src/main/java/dev/kizuna/meridian/piglinsword/clienttest/PiglinSwordClientTest.java) · [客户端入口，第 7–9 行](https://github.com/Kizunad/Kizuna-Meridian-Piglin-Sword-Addon/blob/main/clienttest/src/main/resources/fabric.mod.json#L7-L9) · [Framework 装配清单](https://github.com/Kizunad/Kizuna-Meridian-Mod-Framework/blob/main/scripts/assembly.json)

## 11. 在 dev 容器验收

下例用流金剑主目录演示现成入口。派工时将 `content` 和 `pa` 换成对应工作树。其他依赖仓也有未合改动时，每棵树带一个 `--with "<工作树路径>"`，放在 Gradle 参数分隔符之前。

```sh
code="$HOME/Code"
kz="$code/Kizuna-remote-session/scripts/kz"
content="$code/Kizuna-Meridian-Piglin-Sword-Addon"
pa="$code/Kizuna-Players-Animation"

"$kz" test "$content" --part gametest -- -PgametestFilter=PiglinSwordGameTest
"$kz" test "$content" --with "$pa"
"$kz" gradle "$pa" -- test build -x checkAnimationGates
"$kz" client "$content" p0 --with "$pa"
```

- 定向与全量 GameTest 预期退出 0，kz 显示 `piglin-sword GameTest :gametest:runGametest：过，N/N`。核准每条新用例名在日志或 XML 中出现，并核对新增条数。负对照要在目标断言处失败，随后按字节还原。
- 全量单测与构建预期显示 `单测和构建：过，退出码 0`。PA 日常构建预期 `BUILD SUCCESSFUL`。动作改了还要跑导出一致性任务 `"$kz" gradle "$pa" -- checkAnimationAssets`。对每个改动剪辑，在 PA 工作树执行 `(cd "$pa" && python3 "scripts/analyze_animation.py" piglin_sword_wind_around piglin_sword_wind_around_off)`，核对粗臂、细臂均通过。
- 客户端预期 `piglin-sword p0：过`，输出本次 `clienttest/build/client-p0-…/screenshots`。按项目实际断言、日志和截图核对，而后确认测试进程退出。
- 每次 GameTest 用新运行目录。对固定目录的仓，kz 会清理后重新建立。旧运行配置会保留旧值。同模块 JUnit 的日志放在该模块 `build/`。以上命令在 dev 本机执行。[kz 测试与运行目录，第 598–628 行](../../../Kizuna-remote-session/scripts/kz#L598-L628) · [PA 导出任务，第 79–85 行](https://github.com/Kizunad/Kizuna-Players-Animation/blob/main/build.gradle#L79-L85) · [PA 分析脚本参数](https://github.com/Kizunad/Kizuna-Players-Animation/blob/main/scripts/analyze_animation.py#L1414-L1422)

合前从功法框架运行 `python3 "scripts/check_docs.py"`。对功法框架、人体、生人、PA 和伤害的待合工作树分别运行 `python3 "$HOME/Code/wt-technique-f4/scripts/repo_quickref.py" --check "<仓路径>"`，也可用合并后的功法框架主目录调用同一脚本。预期每仓输出 `速查一致` 并退出 0。速查失配时先重新生成，再复核自动段与手写的「常见坑」。

交付清单最后补一行 `手册增补：本次新摸清的接线和坑 N 条`，没有增补就写 `手册增补：无`。有新经验时附上源码或日志位置，主会话合入手册或各仓速查。
