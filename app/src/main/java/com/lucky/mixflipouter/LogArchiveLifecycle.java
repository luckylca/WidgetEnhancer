package com.lucky.mixflipouter;

import java.io.File;

/** Shared archive-delivery and retention decisions for runtime log ZIPs. */
final class LogArchiveLifecycle {
    static final long RETENTION_MS = 24L * 60L * 60L * 1000L;

    enum Outcome { SAF_SAVE_SUCCEEDED, SAVE_CANCELLED, SHARE_CLOSED, DELIVERY_FAILED, FLOW_CLOSED }

    static boolean shouldDelete(Outcome outcome) {
        return outcome == Outcome.SAF_SAVE_SUCCEEDED;
    }

    /** Applies the archive retention policy after file delivery completes. */
    static boolean completeDelivery(File archive, Outcome outcome) {
        if (archive == null || !archive.exists()) return true;
        if (shouldDelete(outcome)) return archive.delete() || !archive.exists();
        return archive.setLastModified(System.currentTimeMillis());
    }

    static boolean isExpired(long archiveModifiedAt, long now, long leaseModifiedAt,
                             boolean leaseActive) {
        if (leaseActive) return false;
        if (archiveModifiedAt <= 0 || now <= archiveModifiedAt) return false;
        if (now - archiveModifiedAt <= RETENTION_MS) return false;
        return leaseModifiedAt <= 0 || now - leaseModifiedAt > RETENTION_MS;
    }

    private LogArchiveLifecycle() { }
}
