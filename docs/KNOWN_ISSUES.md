# Known issues

1. HyperOS rejects ordinary `adb shell input` injection. Monkey is inconsistent across Material views
   and the dual-display input topology. AOAv2 USB HID works as a physical input fallback, but remains
   a developer test technique rather than an app capability.
2. Shortcut blank taps and bidirectional swipe handoff are fixed in code and covered by runtime
   layout capture, but repeated official/custom/official swipes still need final physical cover-touch regression.
3. MediaSession-ID/API lyrics and the optional native NetEase callback are device-proven on 9.5.61.
   Native lyric offset behavior and future NetEase/Tinker class maps still require version-specific
   regression when the player updates.
4. The optional SystemUI adapter's pre-unlock backoff and post-unlock snapshot recovery are proven.
   An actual reversible advanced tile click still needs verification.
5. Exact property-control polish and the final release bundle remain incomplete. The diagnostics export and editor history/alignment/grid tools are implemented but still need unlocked-device UI regression.
6. Compatibility is proven only on MIX Flip 1 `ruyi`, HyperOS `OS3.0.303.0.WNICNXM`, Android 16. Reflection adapters must fail closed on unknown builds.
7. ADB shell has no KernelSU `su` binary on this phone; operations that truly require root cannot be automated from shell.
