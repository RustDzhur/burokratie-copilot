package de.buerokratiecopilot.memory;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The rights of the person who asks, read where the memory selects what to show. */
final class ReaderRights {

    private ReaderRights() {
    }

    static Set<String> held() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    /** Whether every case of the tenant is open to the reader (see CaseService.accessLevel). */
    static boolean seesEveryCase() {
        return held().contains("cases.assign");
    }
}
