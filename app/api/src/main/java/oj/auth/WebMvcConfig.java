package oj.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：认证拦截器 + 开发环境 CORS（仅本地前端源）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final String[] corsAllowedOrigins;

    public WebMvcConfig(AuthInterceptor authInterceptor,
                        @Value("${oj.cors.allowed-origins:}") String corsAllowedOrigins) {
        this.authInterceptor = authInterceptor;
        this.corsAllowedOrigins = (corsAllowedOrigins == null || corsAllowedOrigins.isBlank())
                ? new String[0]
                : corsAllowedOrigins.split(",");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**", "/internal/**")
                // Judge Gateway 使用代理密钥/mTLS 自认证，不走用户 Bearer 认证
                .excludePathPatterns("/api/judge/v1/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (corsAllowedOrigins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(corsAllowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
