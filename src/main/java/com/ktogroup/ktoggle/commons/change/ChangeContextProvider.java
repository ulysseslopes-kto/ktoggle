package com.ktogroup.ktoggle.commons.change;

import com.ktogroup.ktoggle.commons.time.Ids;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link ChangeContext} of the current HTTP request: the actor comes from the Keycloak JWT
 * ({@code preferred_username}, falling back to {@code sub}), the optional reason from the
 * {@value #REASON_HEADER} header, and the change id is generated once per request.
 */
@Component
public class ChangeContextProvider {

    public static final String REASON_HEADER = "X-Ktoggle-Reason";
    private static final String CHANGE_ID_ATTRIBUTE = ChangeContextProvider.class.getName() + ".changeId";
    private static final int MAX_REASON_LENGTH = 1000;

    public ChangeContext current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            return authentication == null ? system("unknown") : new ChangeContext(Ids.newId(), currentActor(), null);
        }
        UUID changeId = (UUID) attributes.getAttribute(CHANGE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (changeId == null) {
            changeId = Ids.newId();
            attributes.setAttribute(CHANGE_ID_ATTRIBUTE, changeId, RequestAttributes.SCOPE_REQUEST);
        }
        return new ChangeContext(changeId, currentActor(), reason(attributes));
    }

    /** Context for changes initiated by the service itself (jobs, startup reconciliation). */
    public static ChangeContext system(String job) {
        return new ChangeContext(Ids.newId(), "system:" + job, null);
    }

    private static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("preferred_username");
            return username != null ? username : jwt.getToken().getSubject();
        }
        return authentication != null ? authentication.getName() : "anonymous";
    }

    private static String reason(RequestAttributes attributes) {
        if (attributes instanceof ServletRequestAttributes servlet) {
            String reason = servlet.getRequest().getHeader(REASON_HEADER);
            if (reason != null && !reason.isBlank()) {
                return reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason.strip();
            }
        }
        return null;
    }
}
