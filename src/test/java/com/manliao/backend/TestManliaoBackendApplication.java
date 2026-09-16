package com.manliao.backend;

import org.springframework.boot.SpringApplication;

public class TestManliaoBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ManliaoBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
