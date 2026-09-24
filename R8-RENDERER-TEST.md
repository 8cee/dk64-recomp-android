# DK64 Android r8 renderer test

This audit build enables the existing shareable diagnostics logger by default and adds in-app renderer A/B controls.

1. Start with the **system/stock Vulkan driver**.
2. Long-press the app icon -> **Logs de diagnóstico** -> select **Auto**. Close the app completely and reopen it.
3. Reproduce the title/water, Kong/cap and transparency/fog scenes.
4. Open **Logs de diagnóstico** and share the newest log.
5. Select **Legacy**, fully close/reopen, repeat the exact same scenes, and share the new log.
6. If needed, repeat with **Full**.

The buttons manage the same `renderer_compat.txt` override consumed by the native renderer: Auto removes it, Full writes `full`, and Legacy writes `legacy`.

The useful log lines begin with `DK64DIAG`, `plume:` or `RT64:`. In particular record:
- GPU/device and Vulkan driver identity;
- selected renderer path (`auto`, `legacy`, `full`);
- `flat`, `uber` and `dualSource` capability values;
- fallback rung 1b/2/3 messages;
- `full renderer path ... uber pipeline` messages.

If Auto/Full is visually correct and Legacy reproduces the glitches, the old Qualcomm compatibility gate is implicated. If all modes are broken, the logs tell us which shader/pipeline path actually failed.
