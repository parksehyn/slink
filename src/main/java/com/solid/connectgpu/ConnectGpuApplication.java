package com.solid.connectgpu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ConnectGpuApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectGpuApplication.class, args);
    }

}
