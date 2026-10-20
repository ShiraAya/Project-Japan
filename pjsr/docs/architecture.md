# 首版接口核验与实现边界

基线：PJR 0.1.24 完整源包；公开仓库起点 7902b0dbcdf74eb1a9d702431901c92f252557f4。
SR 1.0.15：782df99c06332ee0b87768e07ec686133e4a1220，未修改 SR 仓库。
PJR 基线 JAR SHA-256：71a64c622c6e08ef90447dec8a6e73e0fa2290293c6033f1e4bb1fd4f39b0428。
SR JAR SHA-256：dbc07f17fde7fe7f3055f3dd6b5c08d8edcbc538e109f71afb7c8ccc8913741e。

| 实际入口 | 用途 |
| --- | --- |
| PJR Geo / WorldEngine.geometry | 同一投影及无区块加载的基础地形/水文采样 |
| PjrChunkGenerator.buildSurface | 在已有基础地形上只写当前 ChunkAccess |
| RoadCatalog / RoadPlan | 固定来源、全段纵断面、稳定编辑单元及区块空间索引 |
| SR RoadRecord / RoadGeometry.Mesh | 原生 alignment 与渲染/编辑描述；通过 NBT 规范化后才使用 |
| SR RoadRaster.Local | 原生碰撞栅格，按当前区块提取 |
| SR RoadStructures.plan | 桥梁护栏/支撑；拒绝占据湿地的桥墩 |
| SR RoadIndex.Built.loading / RoadNetwork.broadcastRoad | 主线程登记与原生同步，不执行整路施工 |
| SR RoadIndex.changed | 原生替换/删除/分割触发覆盖日志；不改 SR 生产代码 |
| Forge ChunkDataEvent / ChunkEvent / LevelTickEvent | 区块完成标志、恢复登记、每 tick 最多处理4个待登记区块 |

无道路的旧生成器 codec 缺省 road_snapshot 为空，不会被自动迁移。
世界 SavedData 和独立覆盖日志都锁定快照及哈希。覆盖日志在 SR 回调时原子替换并刷盘；
普通编辑提前记录覆盖。SR 的批量分割会临时屏蔽 changed 回调，因此另外在 index revision
改变时比较本样本已登记的原生记录，并在 tick 结束及世界加载时恢复覆盖。该补偿不扫描玩家全部道路。
它降低单独保存 SR 和生成状态时再次铺回旧道路的风险。未做断电故障注入，不能声称事务级崩溃恢复已经验收。
标线编辑使用 SR 公开入口；集成测试的分割器通过反射调用 SR 内部原始规划/提交，
保留 SR 校验，测试代码不进入发行 JAR。实际玩家权限仍由 SR 工具验证。

纵断面12%坡度上限、12格最大抬升、约96格编辑长度都是本区域样本参数，非全国通用标准。
自动生成不调用 SR 玩家交互施工，不强制加载沿线区块；玩家后续编辑遵循 SR 原有加载语义。
当前 overlay 以有界编辑单元为粒度；更多区域、并行编辑与事故恢复需后续验证。

首次生成时另外使用只包含本样本14条记录的临时 RoadData 调用 SR LaneTopology.initialize，
固化原生自动末端车道接建点；该过程没有 ServerLevel，也不加载方块。
比较玩家覆盖时忽略自动末端点的再派生，保留所有手工点、分割信息、设置及几何差异。
