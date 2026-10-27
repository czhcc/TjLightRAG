package com.trs.app;

import java.security.Principal;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

/** 认证 REST 接口。 @author agent */
@lombok.RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final UserRepository users;

  /** 获取会话 CSRF 令牌。 @param token 令牌 @return 令牌信息 @author agent */
  @GetMapping("/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken());
  }

  /** 返回当前登录用户名。 @param principal 当前用户 @return 用户信息 @author agent */
  @GetMapping("/me")
  public Map<String, Object> me(Principal principal) {
    return Map.of(
        "username",
        principal.getName(),
        "admin",
        users.findByUsername(principal.getName()).map(UserAccount::isAdmin).orElse(false));
  }
}
