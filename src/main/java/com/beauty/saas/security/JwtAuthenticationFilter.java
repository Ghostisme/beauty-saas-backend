package com.beauty.saas.security;

import com.beauty.saas.common.Result;
import com.beauty.saas.common.ApiException;
import com.beauty.saas.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtUtil jwt;
    private final AccessService access;
    private final ObjectMapper mapper;
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/user/login") || request.getMethod().equals("OPTIONS");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                var claims = jwt.getClaimsFromToken(header.substring(7));
                Object tenant = claims.get("tenantId"), version = claims.get("authVersion");
                if (!(tenant instanceof Number) || !(version instanceof Number)) throw new ApiException(401,"请重新登录");
                AccountPrincipal principal = access.load(((Number)tenant).longValue(), Long.parseLong(claims.getSubject()), ((Number)version).longValue());
                var authorities = principal.scopes().keySet().stream().map(SimpleGrantedAuthority::new).toList();
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, authorities));
            } catch (JwtException | IllegalArgumentException | ApiException e) {
                SecurityContextHolder.clearContext();
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                mapper.writeValue(response.getWriter(), Result.error(401,"登录已失效，请重新登录"));
                return;
            }
        }
        chain.doFilter(request,response);
    }
}
