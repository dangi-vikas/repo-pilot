package dev.repopilot.api.v1;

import dev.repopilot.security.AuthTokenService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unauthenticated bootstrap document.
 *
 * <p>Browsers cannot read the token file, so the UI needs to know what to ask the
 * operator for. This endpoint explains the authentication mode and where to find the
 * token. It deliberately <b>never</b> returns the token or any part of it: it is a
 * signpost, not a credential.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthTokenService tokens;

    public AuthController(AuthTokenService tokens) {
        this.tokens = tokens;
    }

    @GetMapping("/bootstrap")
    public Map<String, Object> bootstrap() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authMode", "bearer");
        response.put("header", "Authorization");
        response.put("scheme", "Bearer");
        response.put("tokenGenerated", tokens.isGenerated());
        response.put("tokenLocationHint", tokens.locationHint());
        response.put("instructions",
                "Send 'Authorization: Bearer <token>'. The token is read from REPOPILOT_AUTH_TOKEN, "
                        + "or generated on first run and written to the path described by tokenLocationHint.");
        return response;
    }
}
