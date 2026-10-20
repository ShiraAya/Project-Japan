# PJSR — Project Japan Spline Roads edition

PJR 0.1.24 的独立开发分支，三轴 0.5，Minecraft 1.20.1 / Forge 47.4.20 / Java 17。
本分支的构建项目位于 `pjsr/`；仓库根部旧 PJ 和 `main` 不作修改。

当前初版正在实现与验证：高尾国道20号真实区域原型，离线资源，SR 1.0.15 原生道路。
**当前提交是 WIP，尚未通过 Forge/游戏验收。** 全国数据、复杂高速立交不在此次首版范围。

构建：
1. 在 `pjsr/` 运行 `python3 tools/prepare_base.py`，从仓库现有固定 JAR 分片还原原始地形/水文资源，无需联网。
2. 将已有 `SR-1.0.15.jar` 放在 `pjsr/libs/`（不上传 SR 私有源码或二进制到公开 PJR 仓库）。
3. `./gradlew build`。构建工具需要正常取得 Forge/Minecraft 依赖；游戏运行完全离线。

PJSR 保留 `pjr` 模组 ID 兼容原地理资源/地图，不可与普通 PJR 同时安装。
新建世界选 `PJSR · 日本道路版`；原 `PJR` 预设保持原地形模式。旧存档不自动铺路。
自动道路为世界所有者，编辑、删除、分割使用原 SR 工具，需要创造模式/管理员权限。

OSM 数据：© OpenStreetMap contributors，ODbL 1.0。来源、固定快照和筛选诊断见 `data/roads/`。
