package com.zhijian.demo.inspection;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.unit.DataSize;
import java.io.IOException;

@Configuration
@Profile("inspection")
public class InspectionConfiguration {
    @Bean
    public MultipartConfigElement multipartConfigElement(ArtifactStore store) throws IOException {
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofMegabytes(10));
        factory.setMaxRequestSize(DataSize.ofMegabytes(12));
        factory.setFileSizeThreshold(DataSize.ofBytes(0));
        factory.setLocation(store.tempDirectory().toString());
        return factory.createMultipartConfig();
    }
}
