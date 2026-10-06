# PJR 0.1.15 工作检查点（未完成验收）

按用户额度提醒暂停，不是正式发行版。

已完成：三轴 0.5 投影，332 河 / 205 湖及旧局部资料同步转换；全国 z12、16 个城市 z14 高程；16 个港区岸线离线资源；新的有界瓦片缓存。初轮核心检查及 483 个原始 DEM 对照通过。完整 ForgeGradle 已成功编译并生成重混淆 JAR，最终全量检查尚未完成，不能宣称整体验证通过。

阻断项：最终全域掩膜预览发现静冈、滨松海面仍有矩形伪陆地，详见 docs/coast-source-contact.png。原因待完成验证：GSI 海域要素存在覆盖空洞，整瓦片“全国海洋 + DEM 全缺测”的回填只能修复部分空洞；局部瓦片中的空洞及封闭海域清理仍可能留下矩形干地区。不能直接把所有无高程像素改成海，应结合 Cstline 海岸线保护真实填海地块。

下一步：修复上述来源覆盖空洞，重新编译受影响岸线；更新原始分类验证点，运行 ./gradlew check build；检查实际生成器俯视图；运行 tools/validate_release_binary.py 和 tools/finalize_release_metadata.py 后再正式打包。保留 332 河 / 205 湖，不应回退使用 PJ 旧生成器。

已有 tools/finalize_release_metadata.py、tools/package_release.py 是待运行的发行流程，当前没有通过全部发行门槛。资源已包含，可离线普通构建；重新编译岸线需要按 data/*source-lock.json 重新取得原始数据。source-archives、构建缓存与 JDK 未收入此检查点。

比例已变，测试必须新建世界。没有游戏客户端、PJM 帧率或完整流体 tick 实测。
