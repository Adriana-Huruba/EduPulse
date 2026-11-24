package com.edupulse.retrievalpost;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RetrievalPostApplication {

	public static void main(String[] args) {
		SpringApplication.run(RetrievalPostApplication.class, args);
	}

}
