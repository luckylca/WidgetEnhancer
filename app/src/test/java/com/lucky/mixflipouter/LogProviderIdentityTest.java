package com.lucky.mixflipouter;

import static org.junit.Assert.fail;

import org.junit.Test;

public final class LogProviderIdentityTest {
    @Test
    public void acceptsAllowedPackageOwnedByCallingUidAndMatchingProcess() {
        LogCallerIdentity.validate(10234, new String[]{"com.miui.home"}, null,
                "com.miui.home", "com.miui.home", "com.miui.home");
    }

    @Test
    public void rejectsClaimedPackageNotOwnedByCallingUid() {
        expectSecurity(() -> LogCallerIdentity.validate(10234,
                new String[]{"com.miui.home"}, null, "com.lucky.mixflipouter",
                "com.lucky.mixflipouter", "com.lucky.mixflipouter"));
    }

    @Test
    public void rejectsMismatchedCallingPackageAndClaim() {
        expectSecurity(() -> LogCallerIdentity.validate(10234,
                new String[]{"com.miui.home"}, "com.miui.home", "com.miui.home",
                "com.android.systemui", "com.android.systemui"));
    }

    @Test
    public void rejectsForgedProcessNameEvenWhenPackageIsOwned() {
        expectSecurity(() -> LogCallerIdentity.validate(10234,
                new String[]{"com.miui.home"}, null, "com.miui.home:worker",
                "com.miui.home", "com.miui.home:other"));
    }

    @Test
    public void rejectsNullOrForeignProcessClaim() {
        expectSecurity(() -> LogCallerIdentity.validate(10234,
                new String[]{"com.miui.home"}, null, null, "com.miui.home", null));
    }

    @Test
    public void systemUidMustOwnAndroidAndBeSystemServer() {
        LogCallerIdentity.validate(1000, new String[]{"android"}, null,
                "system_server", "android", "android");
        expectSecurity(() -> LogCallerIdentity.validate(1000,
                new String[]{"android"}, null, "com.android.systemui", "android",
                "system_server"));
        expectSecurity(() -> LogCallerIdentity.validate(1000,
                new String[]{"com.android.shell"}, null, "system_server", "android",
                "system_server"));
    }

    private static void expectSecurity(Runnable action) {
        try {
            action.run();
            fail("expected SecurityException");
        } catch (SecurityException expected) {
            // Expected rejection.
        }
    }
}
