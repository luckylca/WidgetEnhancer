# Known issues

1. The LSPosed Hook has not yet been enabled and physically swipe-tested on the cover screen after migration.
2. VideoView is provisional and not considered lifecycle-complete.
3. Settings catalog injection has not been identified or implemented.
4. The current app UI and data model are the 0.1.0 demo and will be replaced incrementally.
5. The ADB shell cannot invoke root on this device even though KernelSU is active; root-only test steps may require user approval in the manager or an in-process LSPosed bridge.

