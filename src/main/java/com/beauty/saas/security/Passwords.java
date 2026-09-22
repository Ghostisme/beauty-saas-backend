package com.beauty.saas.security;

import com.beauty.saas.common.ApiException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class Passwords {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
    private final String dummyHash = encoder.encode("unusable-dummy-comparison-value");
    public String hash(String value) {
        if (value == null || value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new ApiException(400, "密码至少 8 位，UTF-8 编码后不得超过 72 字节");
        return encoder.encode(value);
    }
    public boolean matches(String value, String encoded) {
        if (value == null || value.length() > 128) return false;
        if (encoded != null && encoded.matches("[a-fA-F0-9]{32}")) {
            String digest = DigestUtils.md5DigestAsHex(value.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8), encoded.toLowerCase().getBytes(StandardCharsets.UTF_8));
        }
        if (value.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        return encoder.matches(value, encoded == null ? dummyHash : encoded);
    }
    public boolean legacy(String hash) { return hash != null && hash.matches("[a-fA-F0-9]{32}"); }
    /** After a successful legacy comparison, upgrade without rejecting a previously valid short password. */
    public String upgradeLegacy(String verifiedValue) {
        return verifiedValue.getBytes(StandardCharsets.UTF_8).length <= 72 ? encoder.encode(verifiedValue) : null;
    }
}
