package com.sstlfsj.fibra.spring.host.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 用 fibra-spring-boot-starter 跑 Fibra 的 Web 宿主示例入口。 */
@SpringBootApplication
public class ExampleSpringHostApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExampleSpringHostApplication.class, args);
    }
}
