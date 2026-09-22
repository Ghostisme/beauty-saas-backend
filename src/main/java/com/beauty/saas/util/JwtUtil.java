package com.beauty.saas.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT工具类
 *
 * @author Beauty SaaS Team
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.expiration}")
    private Long expiration;

    private SecretKey signingKey;

    @PostConstruct
    void initialize() {
        // A development-only ephemeral key avoids shipping a known signing secret.
        signingKey = secret.isBlank() ? Jwts.SIG.HS256.key().build() : Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 获取签名密钥
     */
    private SecretKey getSignKey() {
        return signingKey;
    }

    /**
     * 生成JWT令牌
     *
     * @param userId 用户ID
     * @param tenantId 企业租户 ID
     * @param authVersion 账号会话版本，用于改密和停用后撤销登录
     * @return JWT令牌
     */
    public String generateToken(long userId, long tenantId, long authVersion) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer("beauty-saas")
                .claim("tenantId", tenantId)
                .claim("authVersion", authVersion)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSignKey())
                .compact();
    }

    /**
     * 从JWT令牌中解析Claims
     *
     * @param token JWT令牌
     * @return Claims
     */
    public Claims getClaimsFromToken(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .requireIssuer("beauty-saas")
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从JWT令牌中获取用户ID
     *
     * @param token JWT令牌
     * @return 用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = getClaimsFromToken(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 验证JWT令牌是否有效
     *
     * @param token JWT令牌
     * @return 是否有效
     */
    public boolean validateToken(String token) {
        try {
            Claims claims = getClaimsFromToken(token);
            return !claims.getExpiration().before(new Date());
        } catch (Exception e) {
            return false;
        }
    }
}
