package oj.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spring Security 配置（Task 4 阶段）：
 * 会话无状态；请求级认证与授权由 AuthInterceptor + 服务层对象级授权承担，
 * Task 5 将接入教务网页登录适配器并收紧到短令牌 + CSRF 幂等模型。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }
}
