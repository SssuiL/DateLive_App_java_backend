package com.manliao.backend.identity;

import java.io.IOException;
import java.util.List;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import com.manliao.backend.common.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain security(HttpSecurity http, AuthService auth, AuthRateLimiter limiter, ErrorResponses errors, com.manliao.backend.admin.AdminService admins) throws Exception {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws IOException, ServletException {
                try {
                    limiter.admit(request.getMethod(), request.getServletPath(), request.getRemoteAddr());
                    String header = request.getHeader("Authorization");
                    if (header != null) {
                        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) throw new ApiError(401, "AUTH_MISSING_TOKEN", "缺少登录凭证");
                        Object principal;
                        if(request.getServletPath().startsWith("/admin/")) principal=admins.authenticate(header.substring(7).strip());
                        else principal=auth.authenticate(header.substring(7).strip());
                        var context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
                        SecurityContextHolder.setContext(context);
                    }
                } catch (ApiError error) {
                    errors.write(request, response, error);
                    return;
                } catch (org.springframework.dao.DataAccessException error) {
                    errors.write(request, response, new ApiError(503,"COMMON_SERVICE_UNAVAILABLE","服务暂时不可用"));
                    return;
                }
                chain.doFilter(request, response);
            }
        };
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
            .authorizeHttpRequests(rules -> rules
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/admin/moderation/access/*").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/admin/auth/login").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/media/access/*").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET,"/ws/messages","/ws/notifications").permitAll()
                .requestMatchers("/health", "/health/ready", "/actuator/health").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,
                    "/auth/register","/auth/login","/auth/refresh","/auth/logout","/auth/sms/send","/auth/sms/login","/auth/register/verify","/auth/password/reset").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request,response,error) -> errors.write(request,response,
                    new ApiError(401,"AUTH_MISSING_TOKEN","缺少登录凭证")))
                .accessDeniedHandler((request,response,error) -> errors.write(request,response,
                    new ApiError(403,"COMMON_FORBIDDEN","没有访问权限"))))
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
