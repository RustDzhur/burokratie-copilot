package de.buerokratiecopilot.identity.profile.web;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import de.buerokratiecopilot.identity.profile.ProfileService;
import de.buerokratiecopilot.shared.context.CurrentUser;

/** The signed-in person's own profile: name, language, time zone and photo. */
@RestController
@RequestMapping("/api/v1/profile")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
        type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
@Tag(name = "Profile", description = "The signed-in person's name, language, time zone and photo")
public class ProfileController {

    private final ProfileService profile;

    public ProfileController(ProfileService profile) {
        this.profile = profile;
    }

    public record UpdateRequest(String displayName, String locale, String timeZone) {
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The own profile")
    public Map<String, Object> get() {
        return profile.get(CurrentUser.required());
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Change the own name, language and time zone")
    public Map<String, Object> update(@RequestBody UpdateRequest request) {
        return profile.update(CurrentUser.required(), request.displayName(), request.locale(), request.timeZone());
    }

    @PutMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Set the own photo (PNG, JPEG or WebP, up to 400 KB)")
    public Map<String, Object> setAvatar(@RequestParam("file") MultipartFile file) throws IOException {
        long version = profile.saveAvatar(CurrentUser.required(), file.getBytes());
        return Map.of("avatarVersion", version);
    }

    @GetMapping("/avatar")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The own photo")
    public ResponseEntity<byte[]> avatar() {
        return profile.avatar(CurrentUser.required())
                .map(avatar -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(avatar.contentType()))
                        // Never interpreted as anything but the picture it was checked to be.
                        .header("X-Content-Type-Options", "nosniff")
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                        .body(avatar.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/avatar")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Remove the own photo")
    public ResponseEntity<Void> deleteAvatar() {
        profile.deleteAvatar(CurrentUser.required());
        return ResponseEntity.noContent().build();
    }
}
