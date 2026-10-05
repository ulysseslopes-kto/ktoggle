package com.ktogroup.ktoggle.audit;

/** Result of walking a hash chain: {@code brokenAt} is the first position whose hash or link does not verify. */
public record ChainVerification(boolean valid, long checked, Long brokenAt, String message) {

    public static ChainVerification ok(long checked) {
        return new ChainVerification(true, checked, null, null);
    }

    public static ChainVerification broken(long checked, long position, String message) {
        return new ChainVerification(false, checked, position, message);
    }
}
