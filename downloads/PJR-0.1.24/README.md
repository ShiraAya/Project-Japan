# PJR 0.1.24-alpha

修复明石附近及其他缺失高程边界的直墙，清理 16 个港区的 226 处细长海岸结构。安装包 **49,777,004 字节（49.78 MB）**，所有地理数据内置，运行无需联网。仍为 Minecraft 1.20.1 / Forge 47.4.20 / Java 17。

## 下载完整 JAR

Windows：打开 [download-PJR-0.1.24.cmd](download-PJR-0.1.24.cmd)，选择 **Download raw file** 保存后双击。脚本会下载并合并 7 个分片，核对每片及最终文件的 SHA-256，旁边生成 `ProjectJapanRefined-0.1.24-alpha.jar`。把完整 JAR 放入 mods，删除旧版 PJR；分片本身不能作为模组安装。

其他系统：下载 [download-PJR-0.1.24.py](download-PJR-0.1.24.py)，用 Python 3 运行，无第三方依赖。下载步骤需要联网，模组运行不需要。

最终 SHA-256：`71a64c622c6e08ef90447dec8a6e73e0fa2290293c6033f1e4bb1fd4f39b0428`。

## 安装与验证

修复作用于新生成区块，旧存档已经生成的断墙不会自动改变。建议先建新世界核对。PJM 配套 [1.3.11](https://github.com/ShiraAya/Map-for-Project-Japan/blob/main/downloads/pjm-1.3.11.jar)，仅同步离线地图缓存，生产加载器和界面代码与 1.3.10 相同。

[修复与验证记录](REVIEW-0.1.24.md)；[地形前后对照](awaji-comparison.png)。验证使用实际 Java 地形核心，未运行图形 Minecraft、光照或流体 tick。

[源码更新包](PJR-0.1.24-source-update.zip)覆盖固定的 0.1.23 完整源码，可还原本版源码树。它不是安装包；内含构建、清理与验证代码。发布文件直接保存在源码仓库，没有建立 Release。
