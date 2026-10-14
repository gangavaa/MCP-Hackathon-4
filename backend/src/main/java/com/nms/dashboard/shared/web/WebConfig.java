package com.nms.dashboard.shared.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableScheduling
public class WebConfig implements WebMvcConfigurer {

  /** The Angular dev server (ng serve) on another port calls the API directly. */
  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry.addMapping("/api/**").allowedOrigins("http://localhost:4200").allowedMethods("GET", "POST", "PATCH", "DELETE");
  }

  /** Angular routes are client-side: send their URLs to the app's index.html. */
  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    registry.addViewController("/host").setViewName("forward:/index.html");
    registry.addViewController("/tenants/{tenantId}").setViewName("forward:/index.html");
    registry.addViewController("/farms/{farmId}").setViewName("forward:/index.html");
  }
}
