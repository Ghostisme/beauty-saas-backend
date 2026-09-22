package com.beauty.saas;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 美业SaaS系统启动类
 *
 * @author Beauty SaaS Team
 */
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.beauty.saas.mapper")
public class BeautySaasApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeautySaasApplication.class, args);
        System.out.println("========================================");
        System.out.println("美业SaaS系统启动成功！");
        System.out.println("访问地址：http://localhost:8080/api");
        System.out.println("========================================");
    }
}
