# Geographic data and build dependencies

PJR's Java implementation and Python geographic compiler were written for this rebuild.
No PJ Java class, frozen hydraulic profile, custom river width curve, solved lake gate,
or old hydrology repair output is part of the PJR runtime.

## Geographic inputs

- Rivers: Japan Ministry of Land, Infrastructure, Transport and Tourism (MLIT),
  National Land Numerical Information, W05 river data, 47 prefectural archives.
  <https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-W05.html>
- Lakes: MLIT National Land Numerical Information, W09-05 original GML.
  <https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-W09.html>
- Exact downloaded archive URLs, sizes, and SHA-256 values: `data/source-lock.json`.
- Elevation raster: GSI DEM PNG derived raster supplied in the user's PJ stage-1 archive.
  The original `japan_height_metadata.json` remains alongside the raster. It records
  zoom-9 source tiles and an 8192 x 6656 geographic grid, not an effective 8 m DEM.
  <https://maps.gsi.go.jp/development/ichiran.html>
- Land mask: GSHHG derived raster supplied in the user's PJ stage-1 archive.
  Retained third-party notice: `THIRD_PARTY_LICENSES/GSHHG-LGPL-3.txt`.
  <https://www.soest.hawaii.edu/pwessel/gshhg/>
- The selected river/lake identities and declared geographic connections preserve
  the intended project scope. Missing official physical elevation/depth values for
  selected named lakes may use the physical reference attributes in `selection.json`.
  Those reference values are supplied data, not independently remeasured facts.

The supplied stage-1 source archive SHA-256 is
`550c2a3d73c5b662f117539e0c7ad93ab9736ceca4de2cb0ee74e284692e91ea`.
The later scope reference archive SHA-256 is
`59e4dcdc63d8663bf8062527f2490f2ba4108247eeb8d7f78af6c029c380a3ce`.
Only scope identities and geographic source material are retained from those packages.

## Verified references added in 0.1.3

- Midori reservoir normal pool elevation (177 m): MLIT Kyushu Regional Development Bureau,
  <https://www.qsr.mlit.go.jp/midori/about/kisochishiki.html>, checked 2026-09-17.
  The explicit override and source are stored in `lake-references.properties`; the raw W09 value is retained.
- Dam-location comparison used MLIT W01-14 as a read-only diagnostic reference:
  <https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-W01.html>.
  This dataset is not included in the runtime or source distribution.

## References added in 0.1.4

- Yahagi reservoir: official normal pool EL.298 m, dam crest EL.300 m, capacity
  80,000,000 m3 and full-pool area 2.7 km2, checked 2026-09-19:
  <https://www.cbr.mlit.go.jp/yahagi/01-introduction/profile/index.html>.
  The model maximum depth (2.2 times volume / area) is an estimate, not a measured depth.
- Lake Suwa depth context: International Lake Environment Committee database,
  <https://wldb.ilec.or.jp/Display/html/3548>, checked 2026-09-19.
  Its published 7.2 m maximum and the rounded W09 8 m both resolve to 3 blocks at this scale.
- Non-positive W09 depths now use the existing `data/selection.json` reference if available
  (Nojiri 38.3 m, Haruna 13 m, Ashinoko 40.6 m). Otherwise basin depths remain
  explicit area/terrain-relief estimates. No new survey or bathymetric data is claimed.

## Build platform

Minecraft 1.20.1, Forge 47.4.20, ForgeGradle 6.0.54, Gradle 8.8, Java 17.
Gradle wrapper bootstrap files are standard Gradle distribution tooling.
Minecraft/Forge runtime artifacts are resolved by the build; they are not included
as source implementation in this project.

The user-supplied third-party data notices are retained in `THIRD_PARTY_LICENSES`.

## PJR 0.1.9 local relief and additional waterways

Sameura local relief uses independently retrieved Geospatial Information Authority of Japan (GSI) DEM10B PNG tiles at zoom 14. Derived height data are sampled every 4 model blocks with a 192-block edge blend. Tile URLs and SHA-256 values are in `data/gsi-sameura-source-lock.json`. Original specification: https://maps.gsi.go.jp/development/demtile.html . This is terrain elevation, not lake-bottom bathymetry.

The 16 additional river routes use original MLIT W05 archives; identities, dimensions, source gaps and excluded candidates are in `docs/river-expansion-0.1.9-audit.json`. These are derived geographical data, not copied PJ Java implementation.

## PJR 0.1.10：东京湾与东北岛屿局部地形

海岸及港湾水道：国土地理院最適化ベクトルタイル（PMTiles，2026 年 7 月数据源）。仅提取 WA 水域几何及水体类别，不使用行政边界。
来源与利用说明：https://github.com/gsi-cyberjapan/optimal_bvmap

高程：国土地理院 GSI DEM PNG 瓦片，东京湾 z14、择捉岛 z11、色丹与齿舞 z12。资料日期、分辨率和缺测范围依 GSI 原始数据，不宣称实时实测。
规格：https://maps.gsi.go.jp/development/demtile.html
地理院瓦片：https://maps.gsi.go.jp/development/ichiran.html

加工：独立水域类别筛选、连通港湾水道判定、距离场栅格化、高程插值、局部边界渐变及 0.375 等比投影。港湾内与海相连的 52xx 河川/运河仍使用河流分类，51xx 海域使用海洋分类。数据哈希见 data/gsi-coastal-source-lock.json；逐区域审计见 docs/*-data-audit.json。

国后、择捉、色丹、齿舞仅以实际地理轮廓表示。模组不以岛屿的出现或缺失表达主权归属。

## PJR 0.1.15：实际高程与城市现代岸线

GSI DEM10B PNG：全国 z12、城市 z14；负高程与缺测保留；0.5 m 高程量化和二维差分编码。来源：https://maps.gsi.go.jp/development/demtile.html 。原始 URL / SHA-256 见 `data/precision-source-lock.json`。

GSI 最適化ベクトルタイル PMTiles（2026 年 7 月发布源），只提取 z14 WA5100 海域及 WA5200 河湖约束。来源：https://github.com/gsi-cyberjapan/optimal_bvmap ；编码说明：https://maps.gsi.go.jp/help/pdf/vector/optbv_featurecodes.pdf 。来源哈希见 `data/gsi-coastal-source-lock.json`。

加工包括海陆分类、内陆海碎片和微小离岛清理、约 4 m 海岸采样、双距离场、对数量化和 0.5 等比投影。以上是加工数据，非国土地理院直接发布的游戏地图；地理院资料使用条件与署名适用。现代岸线不代表某一天的最新施工状态。
