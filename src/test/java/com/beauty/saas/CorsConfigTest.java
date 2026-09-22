package com.beauty.saas;

import com.beauty.saas.config.CorsConfig;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigTest {
    @Test void configuredLanOriginIsAllowedWithoutAllowingOtherSites() {
        var source=new CorsConfig().corsConfigurationSource(" http://127.0.0.1:5173, http://192.168.50.8:5173 , ");
        var config=source.getCorsConfiguration(new MockHttpServletRequest("POST","/api/user/login"));
        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("http://192.168.50.8:5173")).isEqualTo("http://192.168.50.8:5173");
        assertThat(config.checkOrigin("http://192.168.50.9:5173")).isNull();
        assertThat(config.checkOrigin("https://untrusted.invalid")).isNull();
        assertThat(config.getAllowCredentials()).isFalse();
    }
}
