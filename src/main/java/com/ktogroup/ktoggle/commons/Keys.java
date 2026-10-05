package com.ktogroup.ktoggle.commons;

import com.ktogroup.ktoggle.commons.exception.ValidationException;
import java.util.regex.Pattern;

/** Business keys are immutable identifiers used in SDK payloads, URLs and cross-references. */
public final class Keys {

    /** Same character set GrowthBook accepts for feature keys. */
    private static final Pattern KEY = Pattern.compile("^[a-zA-Z0-9_.:|-]{1,150}$");

    private Keys() {
    }

    public static String requireValid(String entity, String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw ValidationException.of("%s key '%s' is invalid: use 1-150 chars among letters, digits and _ . : | -"
                    .formatted(entity, key));
        }
        return key;
    }
}
