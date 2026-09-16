package com.manliao.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(excludeName="org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration")
public class ManliaoBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ManliaoBackendApplication.class, args);
	}

}
