package com.lucky.mixflipouter;

/** Pure identity policy for the exported logging endpoint. */
final class LogCallerIdentity {
    private static final int SYSTEM_UID = 1000;
    private static final String[] TARGET_PACKAGES = {
            "com.lucky.mixflipouter", "com.miui.fliphome", "android",
            "com.tencent.wetype", "com.bytedance.android.doubaoime",
            "com.android.systemui", "com.miui.voiceassist", "com.netease.cloudmusic",
            "com.miui.home", "com.miui.personalassistant"
    };

    static void validate(int uid, String[] packages, String callingPackage,
                         String actualProcess, String claimed, String process) {
        boolean trustedPackage = false;
        for (String allowed : TARGET_PACKAGES) {
            if (allowed.equals(claimed)) { trustedPackage = true; break; }
        }
        if (!trustedPackage) throw new SecurityException("Package is not allowed to log");
        if (callingPackage != null && !callingPackage.equals(claimed)) {
            throw new SecurityException("Calling package/claim mismatch");
        }
        if (uid == SYSTEM_UID) {
            if (!"android".equals(claimed) || !("android".equals(process)
                    || "system_server".equals(process) || process.startsWith("android:"))) {
                throw new SecurityException("UID 1000 may only report system_server");
            }
            if (!contains(packages, "android")) {
                throw new SecurityException("UID 1000 does not own android package");
            }
            if (actualProcess != null && !"system_server".equals(actualProcess)) {
                throw new SecurityException("UID 1000 caller is not system_server");
            }
            return;
        }
        if (packages == null) throw new SecurityException("Unknown caller UID");
        if (!contains(packages, claimed)) {
            throw new SecurityException("Caller UID/package mismatch");
        }
        if (process == null || !(process.equals(claimed) || process.startsWith(claimed + ":"))) {
            throw new SecurityException("Caller process/package mismatch");
        }
        if (actualProcess != null && !actualProcess.equals(process)) {
            throw new SecurityException("Actual process/claim mismatch");
        }
    }

    static void validateForwarded(int uid, String actualProcess, String claimed, String process) {
        if (uid != android.os.Process.myUid()
                || !"com.lucky.mixflipouter:logger".equals(actualProcess)) {
            throw new SecurityException("Only the dedicated logger process may forward events");
        }
        boolean trustedPackage = false;
        for (String allowed : TARGET_PACKAGES) {
            if (allowed.equals(claimed)) { trustedPackage = true; break; }
        }
        if (!trustedPackage || process == null || !(process.equals(claimed)
                || process.startsWith(claimed + ":"))) {
            throw new SecurityException("Forwarded event origin is invalid");
        }
    }

    private static boolean contains(String[] values, String value) {
        if (values == null) return false;
        for (String candidate : values) if (value.equals(candidate)) return true;
        return false;
    }

    private LogCallerIdentity() { }
}
