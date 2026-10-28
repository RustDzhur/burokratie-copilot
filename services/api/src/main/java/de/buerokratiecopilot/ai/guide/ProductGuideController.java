package de.buerokratiecopilot.ai.guide;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import de.buerokratiecopilot.shared.context.CurrentUser;
import de.buerokratiecopilot.shared.context.RequestContext;

/** Iris, the assistant that knows the product. Open to every signed-in person; the daily limit is per workspace. */
@RestController
@RequestMapping("/api/v1/ai/guide")
public class ProductGuideController {

    private final ProductGuideService guide;

    public ProductGuideController(ProductGuideService guide) {
        this.guide = guide;
    }

    public record TurnBody(String role, String text) {
    }

    public record AskBody(List<TurnBody> messages, String page, String locale) {
    }

    @PostMapping
    public Map<String, Object> ask(@RequestBody AskBody body) {
        UUID tenantId = RequestContext.tenantId().orElseThrow();
        List<ProductGuideService.Turn> turns = body.messages() == null ? List.of()
                : body.messages().stream().map(turn -> new ProductGuideService.Turn(turn.role(), turn.text())).toList();
        ProductGuideService.Answer answer = guide.ask(tenantId, CurrentUser.required(), turns, body.page(), body.locale());
        return Map.of("available", answer.available(), "text", answer.text(), "open",
                answer.open() == null ? "" : answer.open(), "handoff", answer.handoff(), "reason",
                answer.reason() == null ? "" : answer.reason());
    }
}
