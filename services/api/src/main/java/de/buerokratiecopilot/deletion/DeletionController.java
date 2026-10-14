package de.buerokratiecopilot.deletion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.error.ApiException;
import de.buerokratiecopilot.shared.error.ErrorCode;

/** One door for deleting from a list, one item or many; the right needed depends on what is deleted. */
@RestController
@RequestMapping("/api/v1/bulk-delete")
public class DeletionController {

    public record Request(DeletionService.Kind kind, List<UUID> ids) {
    }

    public record Response(int deleted, List<UUID> refused) {
    }

    private final DeletionService deletion;

    public DeletionController(DeletionService deletion) {
        this.deletion = deletion;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public Response delete(@RequestBody Request request) {
        if (request == null || request.kind() == null || request.ids() == null || request.ids().isEmpty()
                || request.ids().size() > 200) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "A kind and between 1 and 200 identifiers");
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals(request.kind().authority()));
        if (!allowed) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing right " + request.kind().authority());
        }
        int deleted = 0;
        List<UUID> refused = new ArrayList<>();
        for (UUID id : request.ids().stream().distinct().toList()) {
            try {
                deletion.delete(request.kind(), id);
                deleted++;
            }
            catch (ApiException ex) {
                refused.add(id);
            }
        }
        return new Response(deleted, refused);
    }
}
