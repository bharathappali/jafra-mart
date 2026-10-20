package com.jaframart.storefront.api;

import com.jaframart.storefront.security.UserProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class AuthController {
  private final AuthenticationManager authenticationManager;
  private final SecurityContextRepository securityContextRepository;

  public AuthController(AuthenticationManager authenticationManager,
                        SecurityContextRepository securityContextRepository) {
    this.authenticationManager = authenticationManager;
    this.securityContextRepository = securityContextRepository;
  }

  @PostMapping("/auth/login")
  public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                 HttpServletRequest httpRequest,
                                 HttpServletResponse httpResponse) {
    try {
      Authentication auth = authenticationManager.authenticate(
          new UsernamePasswordAuthenticationToken(request.username(), request.password()));
      SecurityContext context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(auth);
      SecurityContextHolder.setContext(context);
      httpRequest.getSession(true);
      securityContextRepository.saveContext(context, httpRequest, httpResponse);
      return ResponseEntity.ok(UserProfile.from(auth));
    } catch (AuthenticationException ex) {
      return ApiError.of(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "bad credentials");
    }
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
    SecurityContextHolder.clearContext();
    HttpSession session = request.getSession(false);
    if (session != null) {
      session.invalidate();
    }
    securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public Map<String, Object> me(Authentication authentication) {
    return UserProfile.from(authentication);
  }

  public record LoginRequest(String username, String password) {}
}
