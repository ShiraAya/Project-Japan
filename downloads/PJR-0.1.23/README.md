# PJR 0.1.23-alpha

完全离线；JAR 49,816,835 字节，小于 50 MB。Minecraft 1.20.1 / Forge 47.4.20 / Java 17。

修复冈山近海方形假陆地、改善水库出口坡面，新增 33 条官方河道；共 389 河、221 湖。原有水位、比例 0.5 和建造高度保持一致。

## Windows 下载

下载本目录的 download-PJR-0.1.23.cmd（点击文件后的 Raw / Download raw file），放在普通文件夹中双击。它从本仓库读取 7 个分片，逐片和整体校验后生成 ProjectJapanRefined-0.1.23-alpha.jar。也可使用 Python 3 运行 download-PJR-0.1.23.py。

分片只是为上传/下载服务，完整 JAR 没有再压缩，也不依赖分片运行。只把拼接完成的 JAR 放进 mods，不能直接安装 part 文件。下载脚本需要联网，模组本身离线运行。

关闭游戏后替换旧 PJR，保留一个版本。在新世界或未生成区块验证；旧区块不自动重塑。配套 PJM 1.3.10 仅更新地图数据，生产 Java 代码与 1.3.9 相同。

详细修复及测试限制见本目录 REVIEW-0.1.23.md。未进行图形客户端、完整 ForgeGradle、光照或流体 tick 测试。
