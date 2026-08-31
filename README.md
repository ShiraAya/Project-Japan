# Project Japan

**Project Japan V1.0.0** is a Minecraft Forge mod focused on recreating the terrain and geographic environment of Japan at a reduced real-world scale.

> [!IMPORTANT]
> **This mod is AI-generated and AI-assisted.**  
> The source code, implementation, debugging, data-processing logic, and portions of the project documentation have been produced or modified with the assistance of generative AI tools.  
> The project is maintained, tested, reviewed, and directed by the project author.

## Project Information

- **Version:** V1.0.0
- **Minecraft:** 1.20.1
- **Mod Loader:** Forge
- **Forge Version:** 47.4.20
- **Project:** Project Japan
- **Development Status:** Active

## About Project Japan

Project Japan aims to recreate the geography of Japan inside Minecraft while keeping the world practical for normal gameplay, exploration, transportation, and large-scale city building.

The project uses real geographic and elevation data as the basis for terrain generation and includes a custom hydrology system for rivers and lakes.

The current world scale is:

```text
X : Y : Z
0.125 : 0.25 : 0.125
```

In other words:

- Horizontal scale: approximately **1:8**
- Vertical scale: approximately **1:4**

This allows Japan to remain recognizable while keeping travel distances and construction scale manageable in Minecraft.

## Main Features

### Japanese Terrain

Project Japan contains terrain derived from Japanese elevation data, including:

- Mountains
- Plains
- Valleys
- Coastal terrain
- Islands
- Large-scale geographic features

Terrain height is processed specifically for the Minecraft world rather than being a direct 1:1 DEM conversion.

### Rivers and Lakes

Project Japan V1.0.0 contains a custom hydrology system designed specifically for the reduced-scale Japanese terrain.

The system includes:

- Major Japanese rivers
- Tributaries
- Lakes
- River-to-river connections
- River-to-lake connections
- Lake outlets
- River mouth handling
- Shoreline blending
- River valley generation
- Riverbank protection
- Water-surface elevation profiles

The hydrology system attempts to maintain continuous downstream flow while preventing unrealistic reverse flow, excessive terrain cutting, and uncontrolled water spreading.

### Terrain–Hydrology Integration

Rivers and lakes are integrated with the surrounding terrain instead of simply being placed on top of the heightmap.

The system can modify surrounding terrain to create:

- River valleys
- Natural banks
- Shorelines
- Lake basins
- River mouths
- Tributary junctions

while attempting to preserve the original terrain wherever possible.

## Design Goal

Project Japan is primarily designed for a long-term Minecraft world focused on:

- Japanese-style city building
- Railways
- Expressways
- Bridges
- Infrastructure
- Regional exploration
- Large-scale construction

Because of the approximately 1:8 horizontal scale, the project does not attempt to reproduce every real-world river, road, or geographic feature.

Instead, features are selected and adjusted according to their importance at Minecraft scale.

## Building

Requirements:

- Java 17
- Minecraft 1.20.1
- Forge 47.4.20

Clone the repository and run:

```bash
./gradlew build
```

On Windows:

```powershell
gradlew.bat build
```

Build output will normally be generated under:

```text
build/libs/
```

## AI Development Disclosure

Project Japan is an **AI-generated / AI-assisted software project**.

Generative AI has been used extensively throughout development, including for:

- Java source-code generation
- Code modification
- Refactoring
- Debugging
- Hydrology algorithm development
- Terrain-generation logic
- Build-error analysis
- Data-processing scripts
- Code review
- Documentation

AI-generated code is not assumed to be correct automatically.

Changes are directed and reviewed by the project author and are tested during development whenever possible.

Users and contributors should therefore treat the codebase like any other experimental software project and independently review code before using it in critical environments.

## Geographic Data and Third-Party Material

Project Japan uses or derives data from third-party geographic datasets and other external sources.

Relevant attribution and licensing information is provided in:

```text
ATTRIBUTION.md
THIRD_PARTY_LICENSES/
```

These files must be retained when redistributing the project where required by the respective licenses or data terms.

Project Japan does not claim ownership of third-party geographic datasets or third-party licensed material.

## Disclaimer

Project Japan is an independent Minecraft modification project.

It is not affiliated with or endorsed by Mojang Studios, Microsoft, the Japanese government, or the organizations providing geographic source data.

Geographic features in Project Japan are adapted for Minecraft gameplay and should not be considered an authoritative geographic representation of Japan.

## Version

Current release:

**Project Japan V1.0.0**
