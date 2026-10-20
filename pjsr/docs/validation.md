# 可重复验证

环境：Linux x86_64，Java 17，Minecraft 1.20.1，Forge 47.4.20，SR 1.0.15。

## 核心及制品

在 `pjsr/` 还原基础资源、放置 SR 依赖之后：

```bash
python3 -m unittest discover -s tools -p 'test_*.py'
./gradlew build
```

Python 用例验证车道总数/方向车道、反向单行、具体机动车权限及非法输入。
Java 核心检查固定来源、0.5投影、稳定单元、负坐标区块索引、坡度、边界、
共享端点/宽度和并行规划一致性。制品预算上限为50,000,000字节。

## 真正 Forge 专用服：两次进程

使用专门的临时 `run/` 工作目录，避免使用日常存档。
准备 `run/server.properties`：

```properties
level-type=pjr\:pjsr
level-name=pjsr-integration-world
server-ip=127.0.0.1
server-port=25586
online-mode=false
view-distance=2
simulation-distance=2
max-tick-time=0
```

阅读并同意 Minecraft EULA 后在测试目录准备对应 eula.txt。
首次测试需要不存在 `pjsr-integration-world`、`pjsr-integration-expected.nbt`
和旧结果文件；重跑时将整个测试目录归档后再建立新的测试目录。

```bash
./gradlew -PpjsrIntegration runServer
# 检查 run/pjsr-integration-result.txt 必须为 PASS。
./gradlew -PpjsrIntegration runServer
# 检查 run/pjsr-integration-restart-result.txt 必须为 PASS。
```

测试服务器自动停止。不要只看 Gradle 成功状态；必须检查上述结果文件及异常日志。
第一次按随机顺序生成/加载269个道路和周边区块，检查14条原生记录、所有接缝的
位置/切线/宽度/标线相位、NBT规范化、568个中心线碰撞采样，然后执行真实SR标线编辑、
原生分割器规划/事务提交和删除。保存完整预期记录再退出。
第二次从磁盘重启，比较所有最终原生记录的完整NBT、数量、身份与删除覆盖。
测试类位于 `src/integration`，只在指定 Gradle 属性时加载，不进入发布JAR。

分割通过测试专用反射访问 SR 内部规划与提交，调用现有校验和事务，不复制 SR 生产源码。
原生玩家工具的权限、距离、UI和多人收包路径仍需游戏内手工验收。

## 必须保留的未验收项

- 单机客户端渲染、实际驾驶、键鼠工具流程、多人同步及光影兼容。
- 掉电/杀进程故障注入、区块写入与原生数据保存之间的事故恢复。
- 玩家在仅生成部分路段时进行复杂编辑，再继续探索的完整组合测试。
- 高速出入口/立交/隧道、跨河水文与国道交叉路口的专门样本。
- 长距离探索性能与全国规模缓存；当前结果只适用于固定小区域。
