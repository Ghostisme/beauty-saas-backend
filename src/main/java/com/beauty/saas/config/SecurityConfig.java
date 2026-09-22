package com.beauty.saas.config;

import com.beauty.saas.common.Result;
import com.beauty.saas.security.*;
import com.beauty.saas.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtUtil jwt, AccessService access, ObjectMapper mapper,
                                 UrlBasedCorsConfigurationSource corsConfigurationSource) throws Exception {
        return http.cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/user/login", "/error").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/platform/tenants").permitAll().anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,e) -> {
                response.setStatus(401); response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getWriter(), Result.error(401,"请先登录"));
            }).accessDeniedHandler((request,response,e) -> {
                response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getWriter(), Result.error(403,"没有操作权限"));
            }))
            .addFilterBefore(new JwtAuthenticationFilter(jwt,access,mapper), UsernamePasswordAuthenticationFilter.class).build();
    }
}
