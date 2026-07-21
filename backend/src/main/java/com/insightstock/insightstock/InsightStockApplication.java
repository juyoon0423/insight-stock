package com.insightstock.insightstock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class InsightStockApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsightStockApplication.class, args);
	}

}
