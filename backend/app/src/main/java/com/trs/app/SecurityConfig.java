package com.trs.app;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import lombok.extern.slf4j.Slf4j;
/** 会话认证及 CSRF 配置。 @author agent */
@Configuration @Slf4j
public class SecurityConfig {
    /** 创建密码编码器。 @return BCrypt 编码器 @author agent */
    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    /** 从数据库加载用户。 @param users 用户仓库 @return 用户服务 @author agent */
    @Bean public UserDetailsService userDetailsService(UserRepository users) {
        return name -> users.findByUsername(name).map(u -> User.withUsername(u.getUsername()).password(u.getPasswordHash()).roles("USER").build()).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
    }
    /** 定义认证边界和响应状态。 @param http 安全构建器 @return 过滤链 @throws Exception 配置错误 @author agent */
    @Bean public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(a -> a.requestMatchers("/api/auth/csrf").permitAll().requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .requestCache(c -> c.disable())
            .exceptionHandling(e -> e.authenticationEntryPoint((q,s,x) -> s.sendError(401)))
            .formLogin(f -> f.loginProcessingUrl("/api/auth/login").successHandler((q,s,a) -> { log.info("Login succeeded for {}", a.getName()); s.setStatus(200); }).failureHandler((q,s,e) -> s.sendError(401)).permitAll())
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q,s,a) -> s.setStatus(204)))
            .build();
    }
}
