package com.matchbox.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Smoke-test endpoint to prove our own code is component-scanned, wired, and served.
 * Throwaway — delete once real controllers exist (step 0.3+).
 */
@RestController
public class PingController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
