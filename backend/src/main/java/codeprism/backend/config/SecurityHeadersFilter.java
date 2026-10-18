package codeprism.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds essential HTTP security headers to every response.
 * <p>
 * Headers added:
 * <ul>
 *   <li>X-Content-Type-Options: nosniff — prevents MIME-type sniffing</li>
 *   <li>X-Frame-Options: DENY — prevents clickjacking via iframes</li>
 *   <li>Referrer-Policy: strict-origin-when-cross-origin — limits Referer leakage</li>
 *   <li>X-XSS-Protection: 0 — disables legacy browser XSS filter (CSP is the modern replacement)</li>
 * </ul>
 * Note: HSTS is intentionally omitted here; it should be added by Nginx / CloudFront
 * once TLS is confirmed stable in production.
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // Disable legacy XSS filter; modern browsers rely on CSP.
        response.setHeader("X-XSS-Protection", "0");

        filterChain.doFilter(request, response);
    }
}
