package de.buerokratiecopilot.legal;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** What the public legal pages need: the operator's data entered in the admin area and the version of the texts. */
@RestController
@RequestMapping("/api/v1/public/legal")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Legal", description = "Imprint data and the version of the legal texts")
public class LegalController {

    private final LegalService legal;

    public LegalController(LegalService legal) {
        this.legal = legal;
    }

    @GetMapping
    @Operation(summary = "The operator's data and the current version of the legal texts (public)")
    public Map<String, Object> view() {
        return legal.publicView();
    }
}
