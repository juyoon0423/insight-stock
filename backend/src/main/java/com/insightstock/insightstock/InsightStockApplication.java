package com.insightstock.insightstock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableScheduling
@SpringBootApplication
public class InsightStockApplication {

	public static void main(String[] args) {
		SpringApplication.run(InsightStockApplication.class, args);
		// Blue-Green 전환 검증(2026-07-31)용 버전 마커 - 로그로 신/구 버전을 구분하기 위함.
		log.info("InsightStock backend started (build marker: blue-green-verification)");
	}

}
