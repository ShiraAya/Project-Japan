# MTS 1.20.1-24.0.0 PJ High-Speed Multiplayer Smooth Fix

This branch is a source patch bundle for **Immersive Vehicles / Minecraft Transport Simulator 1.20.1-24.0.0**.

## Baseline

- Upstream: `DonBruce64/MinecraftTransportSimulator`
- Baseline commit: `ed968e2c26ed8a211a857e03fbf8b1887c14b7f9`
- Patched file: `mccore/src/main/java/minecrafttransportsimulator/entities/instances/AEntityVehicleD_Moving.java`
- Upstream file blob at baseline: `b31a5d20e159686e90e3a8456fbfab71b31f89e6`

## What changed

The original MTS client-side vehicle reconciliation uses a quadratic correction based on cumulative server/client delta error:

`error * abs(error) / 25`, capped at 5 per component.

At high road speeds, normal packet arrival jitter can temporarily create several blocks of apparent error, which makes that correction visible as rubber-banding.

This patch keeps the existing server-authoritative `PacketVehicleServerMovement` protocol and vehicle physics, but changes reconciliation to:

- speed-dependent jitter deadzones;
- gentler capped linear correction;
- a larger tolerance for the local controller/driver;
- stronger emergency recovery for sustained large divergence;
- matching treatment for position, rotation and road pathing.

## Compatibility scope

Not changed:

- packet format or packet registration;
- vehicle physics;
- save/NBT format;
- pack JSON format;
- collision implementation;
- Forge interface layer.

The patch is intentionally limited to the client reconciliation section of `AEntityVehicleD_Moving.java`.

## Files

- `mccore/src/main/java/minecrafttransportsimulator/entities/instances/AEntityVehicleD_Moving.java` — full modified source file.
- `MTS-24.0.0-PJ-HighSpeed-SmoothFix.patch` — unified diff against the baseline.
- `LICENCE.md` — upstream license.

## Build

Apply/copy the modified file into a checkout of the upstream baseline, then build the Forge 1.20.1 target using the upstream project build process.

This bundle does **not** claim to be an official MTS release.
