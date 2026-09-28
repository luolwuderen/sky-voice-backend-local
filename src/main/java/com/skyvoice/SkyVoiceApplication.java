package com.skyvoice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SkyVoiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkyVoiceApplication.class, args);
    }
}
