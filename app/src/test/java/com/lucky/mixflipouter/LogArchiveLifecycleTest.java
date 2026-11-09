package com.lucky.mixflipouter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LogArchiveLifecycleTest {
    @Test
    public void onlySuccessfulSafCopyDeletesTheTemporaryArchive() {
        assertTrue(LogArchiveLifecycle.shouldDelete(
                LogArchiveLifecycle.Outcome.SAF_SAVE_SUCCEEDED));
        assertFalse(LogArchiveLifecycle.shouldDelete(
                LogArchiveLifecycle.Outcome.SAVE_CANCELLED));
        assertFalse(LogArchiveLifecycle.shouldDelete(
                LogArchiveLifecycle.Outcome.SHARE_CLOSED));
        assertFalse(LogArchiveLifecycle.shouldDelete(
                LogArchiveLifecycle.Outcome.DELIVERY_FAILED));
        assertFalse(LogArchiveLifecycle.shouldDelete(
                LogArchiveLifecycle.Outcome.FLOW_CLOSED));
    }

    @Test
    public void expirationKeepsRecentAndLeasedFiles() {
        long now = 50L * 60L * 60L * 1000L;
        assertFalse(LogArchiveLifecycle.isExpired(now - 23L * 60L * 60L * 1000L,
                now, 0, false));
        assertFalse(LogArchiveLifecycle.isExpired(now - 48L * 60L * 60L * 1000L,
                now, now - 1, true));
        assertFalse(LogArchiveLifecycle.isExpired(now - 48L * 60L * 60L * 1000L,
                now, now - 23L * 60L * 60L * 1000L, false));
        assertTrue(LogArchiveLifecycle.isExpired(now - 48L * 60L * 60L * 1000L,
                now, now - 48L * 60L * 60L * 1000L, false));
    }
}
