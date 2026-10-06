# PJR 0.1.15 工作检查点

这是用于继续构建的 checkpoint 分支，不是已发布的正式发行版。

已完成：三轴 0.5 投影；332 河 / 205 湖及旧局部资料同步转换；全国 z12、16 个城市 z14 高程；东京湾及其他 16 个城市岸线资源；新的有界瓦片缓存。已修正 GSI 海岸掩膜的坐标方向，移除了多余的 Y 轴翻转，重新编译 16 个岸线区域，共 4,184 个岸线瓦片，资源约 498 MB。修正后的预览已写入 docs/coast-source-contact.png。

本分支已直接保存完整 0.1.15 源码归档：源码压缩包被拆成 checkpoint-assets/PJR-0.1.15-source.part-* 分片，以避开 GitHub 单个 blob 大小限制。`.github/workflows/pjr-0.1.15-checkpoint.yml` 会在 runner 上按字典序拼回归档、解压，并执行 `./gradlew --no-daemon check build`，构建产物作为 Actions artifact 上传。

本地已完成 ForgeProtoChunkValidation（198 chunks、228,990,762 assertions）及核心资源检查；最终全量 Gradle 检查交给上述 GitHub Actions workflow 复核。比例已变，测试必须新建世界；没有游戏客户端、PJM 帧率或完整流体 tick 实测。
