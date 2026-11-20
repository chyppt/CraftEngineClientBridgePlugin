# CraftEngineClientBridge

CraftEngine 的服务端桥接插件，提供自定义内容目录导出、配方导出、可选客户端通信，以及 Jade 识别载体维护。

**当前版本：1.0.0。此仓库仅包含服务端插件源码，不包含客户端模组、Minecraft 服务端核心或第三方插件 JAR。**

## 环境与依赖

- 编译：JDK 25，使用仓库内 Gradle Wrapper。
- 当前编译目标：Minecraft / Paper 26.1.2。
- 必需插件：CraftEngine；当前编译依赖为 CraftEngine 26.9.1。
- 可选插件：PapersDelight。插件通过适配代码导出其支持的配方，实际支持范围取决于所安装版本。
- 声明 Folia 支持，使用区域和实体调度机制；仍需要在实际服务器组合中验证。

此版本没有证明支持所有 Minecraft、CraftEngine 或 Bukkit 版本，不建议直接把编译目标理解为全版本兼容。

## 功能与边界

- 导出 CE 自定义物品、一级分类、方块状态与支持的配方。
- 可选客户端握手与内容同步；没有安装对应客户端的玩家不会获得客户端注册物品、方块等功能。
- 维护 Jade 所用的 CE 方块识别载体，解析作物展示物品，支持名称覆盖。
- 对接可用的 LeavesX 配方接口与重载通知。普通 Paper/Folia 缺少该接口时，不提供该核心特有的同步功能。
- 包含 PapersDelight 配方适配代码；付费插件及其反编译文件不随仓库分发。

**JEI / REI 的完整自定义物品身份、目录、配方展示依赖匹配的客户端桥接或服务端协议能力。单独安装本插件不等于原版客户端会注册真实自定义物品或方块。** 客户端模组需要单独安装，本仓库不提供客户端构建。

## 安装

1. 备份配置，将构建出的插件 JAR 放入服务端 `plugins/`。
2. 确保 CraftEngine 已安装并能正常加载资源。
3. 重启服务器，按需调整 `plugins/CraftEngineClientBridge/config.yml`。
4. 查看启动日志和 `/cebridge status`；配方相关问题可查看 `/cebridge recipes`。

## 命令

管理权限：`cebridge.admin`，默认 OP。命令别名：`/ceb`。

| 命令 | 用途 |
| --- | --- |
| `/cebridge status` | 查看插件与索引状态 |
| `/cebridge info` | 检查所指向的 CE 方块 |
| `/cebridge recipes` | 查看配方同步诊断 |
| `/cebridge reload` | 重载插件配置与索引 |
| `/cebridge rescan [半径]` | 检查已加载区域中的载体 |
| `/cebridge purge [半径]` | 清理失效载体 |
| `/cebridge refresh [半径]` | 刷新载体与相关索引 |

## 配置

默认配置在 [`src/main/resources/config.yml`](src/main/resources/config.yml)。

- `settings.carriers`：识别载体开关。
- `settings.carrier-scale`：载体缩放。
- `settings.patch-dropped-items`：掉落物名称修补。
- `settings.leavesx-recipe-sync`：核心配方重载联动。
- `display-names`：MiniMessage 显示名覆盖。
- `jade-display-items`：作物等方块的展示物品覆盖。
- `client-mod.enabled`：可选客户端通信开关。

## 构建与测试

Windows PowerShell：

```powershell
.\gradlew.bat clean build --no-daemon
```

Linux / macOS：

```bash
bash ./gradlew clean build --no-daemon
```

输出：`build/libs/CraftEngineClientBridge-1.0.0.jar`。

依赖从 Maven 仓库下载，不需要本机客户端目录。协议共享源码已放入本项目的 `cc.cebridge.wire` 包。

付费 PapersDelight 的可选契约测试需要自行提供合法取得的 JAR：

```powershell
.\gradlew.bat test -PpdJar="/path/to/PapersDelight.jar"
```

未提供时，相应测试按测试代码中的条件跳过。单元测试和构建通过不代表所有客户端界面、协议组合及生产环境性能均已验收。

## 许可证

[MIT](LICENSE)。第三方项目及其资源的权利归原作者所有。

### 完整 CE 运行环境测试

染色与锻造配方测试会触发 CE 的反射初始化，需要完整 CE 插件及 Minecraft 运行库（包含 CE 重定位依赖）。默认构建不执行这两个测试类，其余测试正常执行。要运行它们，请提供运行库路径列表（Windows 用分号，Linux 用冒号分隔）：

```powershell
.\gradlew.bat test -PceTestRuntimeClasspath="/path/to/ce-runtime.jar;/path/to/minecraft-runtime.jar"
```

运行库由使用者自行准备，本仓库不分发这些第三方文件。
