package dev.owlibi.reference.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the Apple macOS HIG interactive workspace concept mockup
 * cleanly from the Spring Boot backend at /mockup/.
 */
@Configuration
public class MockupWebConfiguration implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/mockup/**")
                .addResourceLocations("classpath:/mockup/");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/mockup").setViewName("redirect:/mockup/");
        registry.addViewController("/mockup/").setViewName("forward:/mockup/index.html");
    }
}
