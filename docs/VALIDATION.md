# Development validation — 2026-09-08

This is an interim record, not an acceptance report for GTNH 2.8.4. Full gameplay correctness, GUI visual checks and the requested end-to-end performance target remain unverified.

## Automated unit tests

Release 0.1.0: `test reobfJar` passed with 31 tests across 14 classes. Additional coverage includes bus-operation net transfers, vanilla held-stack copying/session identity, and canonical Fluid Drop tag construction. The older 24-test baseline below documents the initial verification stage; it is not the current total. GUI rendering and in-game behavior of the latest changes still require manual validation.

`test` passed: 24 tests, 11 classes, zero failures/errors. The tests cover exact metadata/NBT identity and persistence (including NaN bits and empty typed lists), hash collisions, defensive copies, primitive counters, time-series rollups, nested transfer filtering, external storage residuals, synchronous save fairness/failures, world restoration, deduplicated lineage queries, gaps, packet bounds and the charge/link-preserving upgrade recipe.

Detailed generated report: `build/reports/tests/test/index.html`.

## Actual Forge/Mixin transformation

The separate `src/smoke` mod was compiled with `smokeJar` and loaded through `runClient21`. It explicitly loads all target classes with Minecraft's `Launch.classLoader`, then inspects their injected methods. The normal Forge post-initialization phase and return to the main menu completed; the harness shut the client down normally. Gradle exited successfully.

```
PASS appeng.me.storage.NetworkInventoryHandler injected handlers=3
PASS appeng.me.cluster.implementations.CraftingCPUCluster injected handlers=4
PASS appeng.me.storage.MEMonitorIInventory injected handlers=6
PASS com.glodblock.github.inventory.MEMonitorIFluidHandler injected handlers=6
PASS transformed all requested targets using the actual Forge launch class loader
```

Raw report: `run/client/inspector-transformation-smoke.txt`. Launch log: `build/gradle-smoke-run.log`.

The launch used pinned AE2 `rv3-beta-695-GTNH`, AE2FC `1.4.120-gtnh` and UniMixins `0.1.23`. Other development dependencies are supplied by the GTNH Gradle convention; this is **not** the complete GTNH 2.8.4 modpack. The test proves that the four classes transform, not that every transfer/crafting/device scenario produces correct statistics.

The standalone dedicated-server task did not launch because its Minecraft EULA gate was not accepted. The client and its integrated-world path remain available for further testing.

## Build artifacts

`reobfJar` produced `build/libs/aeinspector-0.1.0-dev.jar`. Its development counterpart has the `-dev.jar` classifier. The manifest, both Mixin configs, refmap, translations and item texture were inspected. The independent smoke-test mod is not included in the main artifact.

These are experimental builds; do not treat them as the requested finished release.

## Performance evidence so far

Only the primitive accumulator has a recorded benchmark: 5000 calls/tick, 10000 keys, 100000 items/call on Ryzen 9 5900X and Oracle Java 21.0.11; p95 0.0229 ms/tick. This excludes NBT resolution, hooks, history aggregation, persistence and viewers, so it does not establish the required 1 ms/tick end-to-end p95.

## Outstanding acceptance work

- Actual partial transfers, processing/molecular crafting intermediates, cancellation, container items, storage buses, fluids, Discretizer and subnet topology.
- Rollback netting for a fluid bus that extracts more than its destination accepts and returns the remainder.
- Configured inactive devices and device matching rules.
- Visual/interactive validation, complete labels/totals/estimate indicators, multiple viewers and query budgeting.
- Full GTNH 2.8.4 load/stress tests, complex/unique/mutated NBT, memory/GC, slow disks and durability failure cases.
- Final release JAR, installation instructions and completed correctness/performance report.
