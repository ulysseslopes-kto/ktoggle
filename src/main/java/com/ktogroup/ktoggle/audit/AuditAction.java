package com.ktogroup.ktoggle.audit;

public enum AuditAction {
    CREATE,
    UPDATE,
    DELETE,
    ARCHIVE,
    UNARCHIVE,
    TOGGLE,
    UPDATE_RULES,
    RESTORE_REVISION,
    PUBLISH_DRAFT,
    BYPASS_PUBLISH_DRAFT,
    PUBLISH_BUNDLE,
    ROLLBACK_BUNDLE,
    UNPIN_BUNDLE
}
