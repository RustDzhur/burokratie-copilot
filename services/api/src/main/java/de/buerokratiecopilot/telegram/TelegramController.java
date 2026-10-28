package de.buerokratiecopilot.telegram;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The person's own Telegram connection: link it, choose how much to hear, test it, remove it. */
@RestController
@RequestMapping("/api/v1/telegram")
public class TelegramController {

    public record Preferences(TelegramService.Level level, Boolean enabled) {
    }

    private final TelegramService telegram;

    public TelegramController(TelegramService telegram) {
        this.telegram = telegram;
    }

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> status() {
        return telegram.status();
    }

    @PostMapping("/link-code")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> linkCode() {
        telegram.createLinkCode();
        return telegram.status();
    }

    @PutMapping("/preferences")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> preferences(@RequestBody Preferences preferences) {
        telegram.preferences(preferences.level(), preferences.enabled());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> test() {
        telegram.sendTest();
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/link")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> unlink() {
        telegram.unlink();
        return ResponseEntity.noContent().build();
    }
}
