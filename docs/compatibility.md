# 兼容性验证记录

目标为 Paper / Folia 1.20+ 与 CraftEngine 26.7.0+。插件字节码目标 Java 17；本地构建工具链使用 JDK 25。CraftEngine 26.7.0 在 Maven 仓库的版本号为 `26.7`。

## 已验证编译矩阵

编译脚本：`scripts/check-compatibility.py`。每项均以目标 Paper API 和对应 CE Maven 版本运行 `compileJava`，产物 release 17。

| 范围 | 结果 |
| --- | --- |
| CraftEngine 26.7、26.7.1-26.7.4、26.8-26.8.2、26.9、26.9.1、26.10 | 全部通过，Paper API 1.20 |
| Paper 1.20、1.20.1、1.20.2、1.20.4、1.20.6、1.21.1、1.21.4、1.21.8、26.1.2 | 全部通过，CraftEngine 26.7 API |

这是代表版本的源码/API 编译检查，不等同于每个补丁版本、每个 CE x Paper 组合的运行认证。后续发布版本可通过脚本追加参数继续检查。

## 运行验证

已在隔离目录以 Java 21 启动 Paper 1.20.1 build 196 + CraftEngine 26.7 + 插件 1.0.0：三个插件均成功启用，CE 重载后索引到 36 个方块，服务端进入 `Done` 并正常关闭。此测试没有真实客户端连接，也未验证配方查看器、Jade/WTHIT、Folia 或 PD 集成。

以 Java 17 启动该 CE 26.7 组合失败，是 CraftEngine 本体要求 Java 21，而非插件字节码问题。Java 实际要求须服从所选 MC 与 CE 的要求。

## 调度策略

Folia 上使用实体、区域、全局与异步调度器；无 Folia API 的旧 Paper 通过反射隔离回退到 Bukkit 主线程/异步调度。Folia world access 在所属区域检查后执行，拒绝跨区域或未加载区块，不主动加载区块。当前未在 Folia 实服运行验证。

## 客户端边界

本次只修改服务端插件与发布仓库文档。Fabric / NeoForge 模组版本矩阵、所有 1.20+ 客户端目标、JEI / REI 实际页面、资源包缓存行为均未在本次扩展。不能根据服务端编译通过推断这些客户端组合已完成验收；客户端构建仍需独立覆盖各 MC / loader API 变更。
