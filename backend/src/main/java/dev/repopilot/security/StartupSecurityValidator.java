package dev.repopilot.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Refuses to start an insecurely exposed instance.
 *
 * <p>RepoPilot is local-first: the default bind address is loopback. Exposing the
 * HTTP API on a routable interface without an explicit, strong token would hand
 * repository contents and analysis control to anyone who can reach the port. Two
 * conditions therefore fail startup rather than warning:
 *
 * <ol>
 *   <li>binding to a non-loopback address without {@code repopilot.security.allow-remote-bind=true};</li>
 *   <li>binding to a non-loopback address with a token RepoPilot had to generate
 *       itself, because the operator never saw the value and cannot rotate it.</li>
 * </ol>
 *
 * <p>This runs before the web server starts so the port is never opened in an
 * unsafe configuration.
 */
@Component
public class StartupSecurityValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(StartupSecurityValidator.class);

    private final Environment environment;
    private final AuthTokenService tokenService;

    public StartupSecurityValidator(Environment environment, AuthTokenService tokenService) {
        this.environment = environment;
        this.tokenService = tokenService;
    }

    @Override
    public void afterPropertiesSet() {
        String address = environment.getProperty("server.address", "127.0.0.1");
        boolean remoteBindRequested = environment.getProperty(
                "repopilot.security.allow-remote-bind", Boolean.class, false);

        boolean loopback = isLoopback(address);
        if (!loopback && !remoteBindRequested) {
            throw new IllegalStateException(
                    "server.address=" + address + " is not a loopback address, but "
                            + "repopilot.security.allow-remote-bind is false. RepoPilot refuses to expose an "
                            + "authenticated-but-public analysis service by default. Set "
                            + "REPOPILOT_SECURITY_ALLOW_REMOTE_BIND=true together with an explicit "
                            + "REPOPILOT_AUTH_TOKEN if you intend to expose it.");
        }

        if (!loopback && tokenService.isGenerated()) {
            throw new IllegalStateException(
                    "Remote binding requires an operator-supplied token. RepoPilot generated its own token "
                            + "on this start, which means it has never been reviewed or rotated. Set "
                            + "REPOPILOT_AUTH_TOKEN to a strong value (>=16 characters, ideally generated with "
                            + "a password manager) before exposing the API on " + address + ".");
        }

        if (!loopback) {
            log.warn("RepoPilot is bound to {} and reachable from the network. Confirm that TLS termination, "
                    + "network policy and token rotation are in place. RepoPilot does not terminate TLS itself.",
                    address);
        } else {
            log.info("RepoPilot bound to loopback ({}). Remote exposure is disabled by configuration.", address);
        }
    }

    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            // An unset server.address means Netty binds all interfaces.
            return false;
        }
        if ("localhost".equalsIgnoreCase(address)) {
            return true;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException e) {
            // An unresolvable bind address will fail elsewhere with a clear error; treat
            // it as non-loopback so the stricter validation path applies.
            return false;
        }
    }
}
