package com.finsight.finsight_ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FinsightAiApplication {

	public static void main(String[] args) {
		SpringApplication.run(FinsightAiApplication.class, args);
	}

}
