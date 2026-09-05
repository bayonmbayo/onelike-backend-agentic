package com.onelike.app;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;


@SpringBootApplication(exclude = SqlInitializationAutoConfiguration.class)
@EnableScheduling
@EnableCaching
public class OnelikeBackendApplication {

	public static void main(String[] args) {
		java.security.Security.setProperty("networkaddress.cache.ttl", "60");
		SpringApplication.run(OnelikeBackendApplication.class, args);
	}
}
