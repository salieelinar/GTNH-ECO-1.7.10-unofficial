<p align="center"><img src="https://raw.githubusercontent.com/DancingSnow0517/NeoECOAEExtension/main/images/logo.png" alt="Neo ECO AE Extension"></p>
<h1 align="center">Neo ECO AE Extension</h1>
<p align="center"><strong>GT New Horizons · Applied Energistics 2 扩展</strong></p>

<p align="center"><strong>简体中文</strong> · <a href="README.en.md">English</a></p>

> **个人修复版**：基于上游 7.3.12，保留上游全部修复与优化，并加入全局进度条等本地改动。改动见 [CHANGELOG](CHANGELOG.md)，下载见 [Releases](https://github.com/salieelinar/GTNH-ECO-1.7.10-unofficial/releases)。

本项目是 Neo ECO AE Extension（ECO）的官方 Minecraft 1.7.10 版本，面向 GT New Horizons。

为 ME 网络提供可扩展的存储、合成与计算多方块，支持集中仓储、批量制造与复杂任务规划。

## 严格一份一份发（1.0.9）

**为什么这样做**：有些机器（注魔、装配线、梦魇工作台、细菌培养缸等）会把多余或混入的材料直接没收。ECO 原来按剩余需求翻倍发配时，多份材料或同一接口的其它配方材料会一起进机器，被吞掉后合成卡死。上游 issue #19 的修复方向是「关闭接口的『样板优化』→ 逐份派发」，但逐份仍可能在机器没加工完时继续收下一份。本版关闭开关后改为**严格串行**：发一份 → 等这份产物回收进 AE → 再发下一份，期间该接口的其它配方暂停，保证机器里始终只有一份材料。实测（细菌培养缸 4 个串联配方混合）发 1 收 1，不再吞材料。

- 适用：AE2 接口、GTNL 超级样板总成/超级接口，关掉它们自己的「样板优化」即生效。
- 不适用：装配矩阵（它的开关恒关闭是 GTNL 排除自家优化器的语义，不是用户开关）、GT 原版 CRIB / TenkeiTech 舱（没有这个开关）。
- 代价：该接口吞吐会下降，只建议给会吞材料的机器用；严格模式只在 ECO 的合成派发内生效。

后续修复todolist
1、完善镜像总成检测机制。
2、引入泛洪算法。
3、渲染兼容安洁莉卡。

## 版本支持

<div align="center">
<table align="center">
  <thead>
    <tr><th align="center">GTNH</th><th align="center">下载</th></tr>
  </thead>
  <tbody>
    <tr>
      <td align="center">2.9.0-stable 及以上</td>
      <td align="center"><a href="https://github.com/Yang120231/GTNH-ECO-1.7.10/releases"><img src="https://img.shields.io/github/v/release/Yang120231/GTNH-ECO-1.7.10" alt="最新版本"></a></td>
    </tr>
  </tbody>
</table>
</div>

必需前置包括 Applied Energistics 2、AE2 Fluid Crafting（AE2FC，1.5.64-gtnh 及以上）、ModularUI2 和 StructureLib。

单方块集成工作站与大型集成工作站均可通过 CraftTweaker 1.7.10 脚本独立添加、删除和清空加工配方，参见 [中文接口说明与示例](docs/CRAFTTWEAKER_ZH_CN.md)。

计算子系统优先用主机或计算通讯端口接入 ME。单点接入的旧结构仍可沿用原有接线；多点接入保留原生 AE2 拓扑，避免默默切断网段。参见 [结构验收与性能对照](docs/COMPUTATION_TOPOLOGY_ZH_CN.md)。

## 功能展示

### 实时监控

![实时监控](https://github.com/user-attachments/assets/c7a6b4d9-7ff9-4a11-85ea-c5793d7a1014)

提供任务库存与加工状态监控，展示关联机器、实际并行、加工进度、剩余时间及可用的停机原因。支持样板定位、远程打开机器与样板供应器，以及任务暂停、跟踪和取消。

### ECO 规划器

![ECO 规划器](docs/images/zh_cn/eco-planner.png)

提供快速规划、循环配方处理与大订单规划，集中展示材料需求、库存使用和合成次数。支持 CPU 选择、任务合并、开始并跟踪，以及缺料加入 NEI 书签。

安装 NEE 后，Ctrl 补料预览与 Alt 直接补料会将多种缺失材料合并为一个临时订单，由 ECO CPU 执行，交付后自动清理。取消会停止后续下单并退回任务库存；网络暂时装不下的材料保留在 CPU 中等待交付。需要 AE2 原生规划语义的配方保留 NEE 的逐项下单流程，并适配 ECO 确认页的开始、取消及临时样板清理。

ECO 工作站配方支持 NEE 材料状态提示、补料快捷键和样板候选材料切换；存储控制器界面支持 NEI 书签批量取料与配方链库存查询。存储接口和计算接口的类型过滤槽遵循 NEE 的拖放及保留幽灵物品设置，过滤槽不保存材料数量。

### 规划图

![规划图](docs/images/zh_cn/planning-graph.png)

提供配方依赖可视化，展示从目标产物到原料的合成关系。支持拖动缩放、节点展开与收起、深度切换、缺料筛选和搜索定位。

### 可拓展计算子系统

![可拓展计算子系统](docs/images/zh_cn/computation-subsystem.png)

提供合成 CPU 所需的任务线程、并行加速和存储空间，支持按玩家或自动化请求筛选任务。C9 支持多机组网，汇总线程、并行数与任务存储。

### 可拓展存储子系统

![可拓展存储子系统](docs/images/zh_cn/storage-subsystem.png)

提供可扩展的物品与流体存储，支持 L4、L6、L9 三级主机、矩阵管理、资源过滤与库存转移。L9 提供无限存储模式及配套的库存迁移与恢复功能。

### 可拓展合成子系统

![可拓展合成子系统](docs/images/zh_cn/crafting-subsystem.png)

提供样板存储与批量合成能力，支持 FX 多通道执行、并行扩展、超频和主动冷却。F9 支持多机组网，共享样板并分配任务。
