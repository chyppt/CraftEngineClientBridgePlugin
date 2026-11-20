# CraftEngineClientBridge

通过服务端插件与客户端模组协作，将 CraftEngine 内容注册为客户端真实物品与方块，支持创造栏、Jade、WTHIT、JEI / REI 及 Paper’s Delight 配方，并提供资源包缓存、实体渲染替代与 MoreCulling 兼容修复。

**版本：1.0.0 · 作者：chy**

> 本仓库仅包含服务端插件。完整功能需要搭配对应版本的客户端桥接模组；客户端模组、资源包、服务端核心及第三方插件不在此仓库中分发。

## 功能

客户端显示与识别功能由配套模组处理。插件负责导出和同步服务端内容，不再监听掉落物生成事件或修补掉落物名称；旧配置中的 `patch-dropped-items` 已不再生效。

### 客户端物品与方块

- 同步 CraftEngine 的物品、分类与方块状态，为客户端提供真实的自定义内容身份。
- 在创造物品栏显示 CE 一级分类，支持物品浏览、搜索及创造模式取物。
- 按自定义物品身份展示内容，减少原版承载物品 ID、图标与名称对浏览和配方查询的影响。
- 客户端桥接为可选安装：未安装的玩家继续使用服务器原有的 CE 内容，不启用对应客户端扩展。

创造模式取物仍受游戏模式、服务端权限及相关插件规则约束。

### Jade 与 WTHIT

- 显示 CE 自定义方块的名称、图标及可用状态信息。
- 结合方块状态与作物映射，展示对应作物信息，避免把作物直接显示为其原版承载方块。
- 支持显示名和展示物品覆盖，便于处理资源包中名称或作物产物映射不一致的内容。

### JEI 与 REI

- 同步 CE 物品目录与支持的配方，按自定义物品身份查询配方和用途。
- 导出有序/无序合成、熔炼、烟熏、高炉、营火、切石、锻造，以及支持的染色配方。
- 对接 **Paper’s Delight（PapersDelight）** 的砧板与厨锅配方，在相应类别中查看切割与烹饪内容。
- 保留可选的 LeavesX 配方接口对接；安装对应客户端桥接时，内容目录通信不要求服务端必须使用 LeavesX。

Paper’s Delight 免费版与付费版的适配取决于其具体 API / 二进制结构。仓库保留相关适配与契约测试，不包含付费插件文件。

### 资源包与渲染

以下功能由插件提供内容数据，并由配套客户端完成处理：

- **资源包缓存**：复用已缓存资源，减少重复下载；资源发生变化时仍需要更新与加载。
- **实体渲染替代**：对受支持的 CE 内容使用客户端方块表现，剔除大部分可替代的显示实体；家具、特殊交互等内容可能仍保留实体。
- **MoreCulling 兼容**：针对特定方块状态剔除场景修正判断，解决煎锅与炉灶等组合中出现的部分顶部透明问题。

资源包缓存不等于所有进服场景都能免加载；缓存命中与否还取决于资源标识、内容变化、客户端缓存及服务端下发方式。

## 运行要求

| 组件 | 要求 |
| --- | --- |
| 服务端 | 当前源码面向 Paper 26.1.2 API；可用于匹配版本的 LeavesX 等 Paper 分支 |
| Java | JDK / JRE 25 |
| CraftEngine | 必需；当前编译依赖为 26.9.1 |
| 客户端桥接模组 | 完整功能需要安装与 Minecraft、加载器和协议匹配的版本 |
| Jade / WTHIT | 按需安装，用于方块信息展示 |
| JEI / REI | 按需安装，用于物品与配方浏览 |
| PapersDelight | 可选，用于砧板与厨锅配方 |

插件使用区域和实体调度并声明 Folia 支持。当前仓库编译目标不代表已经验证所有 Minecraft、CE、加载器或 Folia 组合；客户端兼容版本以对应客户端构建为准。

## 安装

### 服务端

1. 安装并确认 CraftEngine 能正常加载自定义内容与资源包。
2. 将 `CraftEngineClientBridge-1.0.0.jar` 放入 `plugins/`，重启服务器。
3. 按需安装 PapersDelight，让其先正常加载砧板和厨锅配方。
4. 检查 `plugins/CraftEngineClientBridge/config.yml`，保留 `client-mod.enabled: true`。
5. 查看启动日志与 `/cebridge status`，确认 CE 索引和插件初始化结果。

### 客户端

1. 安装匹配游戏版本及加载器的客户端桥接模组。
2. 按需安装 Jade、WTHIT、JEI 或 REI，以及需要使用的 MoreCulling。
3. 连接服务器并完成内容同步与资源包加载。
4. 查看创造栏 CE 分类、方块信息及配方页面。

仅部署本仓库的插件不会让未安装桥接模组的客户端完成真实物品与方块注册。此仓库也不提供客户端模组的下载产物。

## 使用与更新

- 日常浏览：进入创造模式，在 CE 一级分类中查找内容；在 JEI / REI 中查询对应物品的配方或用途。
- 砧板与厨锅：确认 PapersDelight 已加载目标配方，再在配方查看器中查看切割或烹饪类别。
- 修改插件配置：执行 `/cebridge reload`。
- 修改 CE 资源或配方：先按 CraftEngine 的流程完成重载，再检查桥接日志中的物品、配方与状态导出结果。
- 大规模更新前备份配置，并在测试服核对客户端显示与交互。

## 管理命令

权限：`cebridge.admin`，默认 OP；别名：`/ceb`。

| 命令 | 用途 |
| --- | --- |
| `/cebridge status` | 查看插件与内容索引状态 |
| `/cebridge info` | 查看目标 CE 方块的诊断信息 |
| `/cebridge recipes` | 查看配方导出与同步诊断 |
| `/cebridge reload` | 重载插件配置与内容索引 |

## 配置

默认配置见 [`config.yml`](src/main/resources/config.yml)。主要配置：

| 配置项 | 用途 |
| --- | --- |
| `client-mod.enabled` | 启用可选客户端桥接通信 |
| `settings.leavesx-recipe-sync` | 启用可用的 LeavesX 配方重载联动 |
| `settings.auto-name` | 对缺少名称的内容提供 ID 名称兜底 |
| `display-names` | 按 CE ID 覆盖 MiniMessage 名称 |
| `jade-display-items` | 覆盖方块对应的展示物品，例如作物产物 |

显示覆盖示例：

```yaml
display-names:
  "example:ruby_ore": "<red>红宝石矿石</red>"

jade-display-items:
  "farmersdelight:cabbages": "farmersdelight:cabbage"
```

## 排查

| 现象 | 检查项 |
| --- | --- |
| 创造栏没有 CE 分类 | 客户端桥接版本、`client-mod.enabled` 与握手/目录同步日志 |
| JEI / REI 没有目标配方 | 查看器与桥接版本、CE 配方导出统计、目标配方类型 |
| 砧板或厨锅配方缺失 | PapersDelight 是否先加载成功、版本适配日志、目标配方是否有效 |
| 方块名称或作物图标异常 | CE ID、方块状态、`display-names` 与 `jade-display-items` |
| 资源包重复下载或加载 | 资源标识/内容是否变化、缓存是否命中、服务端是否重复下发 |
| 使用 MoreCulling 后局部透明 | 提供方块组合、状态、客户端与 MoreCulling 版本，确认是否属于已适配场景 |

报告问题时请附服务端、CraftEngine、客户端桥接与相关模组版本，以及 CE 物品/方块 ID 和对应日志。配方导出成功不等于客户端页面已经验收。

## 构建

需要 JDK 25。Windows PowerShell：

```powershell
.\gradlew.bat clean build --no-daemon
```

Linux / macOS：

```bash
bash ./gradlew clean build --no-daemon
```

产物：`build/libs/CraftEngineClientBridge-1.0.0.jar`。

依赖通过 Maven 仓库解析；构建不引用开发者的本地客户端目录。插件需要的共享协议代码位于 `cc.cebridge.wire`。

## 测试范围

默认构建执行可独立运行的测试。以下测试需要自行提供运行环境：

- **付费 PD 契约测试**：未提供 JAR 时跳过。
- **CE 染色与锻造测试**：依赖完整 CE / Minecraft 运行库，默认不启用。

```powershell
# 付费 PD 契约测试
.\gradlew.bat test -PpdJar="/path/to/PapersDelight.jar"

# 完整 CE 运行环境测试；Windows 路径之间用分号，Linux 用冒号
.\gradlew.bat test -PceTestRuntimeClasspath="/path/to/ce-runtime.jar;/path/to/minecraft-runtime.jar"
```

测试运行库由使用者自行准备。本仓库的构建和单元测试不能代替真实客户端、跨服资源包缓存、完整模组组合与生产性能测试。

## 许可证

[MIT](LICENSE)。第三方项目、插件与资源的权利归原作者所有。
