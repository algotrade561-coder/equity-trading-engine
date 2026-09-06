package com.equity.api;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the single-page console for paths the router owns.
 *
 * <p>The console uses real URLs — {@code /positions}, {@code /settings} — rather than hash
 * fragments, so a reload or a pasted link asks the server for a path that has no controller. Without
 * this that is a 404, and the deep link everyone eventually shares is broken.</p>
 *
 * <p>Only the known routes are forwarded, deliberately. A blanket catch-all would swallow genuine
 * 404s from the API and return HTML to a fetch client, which turns a typo in an endpoint into a JSON
 * parse error somewhere unrelated.</p>
 */
@Configuration
public class SpaForwardingConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String route : new String[]{"/positions", "/rejections", "/universe", "/settings", "/login"}) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }
}
