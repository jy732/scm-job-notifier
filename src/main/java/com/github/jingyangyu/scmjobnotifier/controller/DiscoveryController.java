package com.github.jingyangyu.scmjobnotifier.controller;

import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchDiscoveryService;
import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchDiscoveryService.DiscoveryReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * On-demand trigger for the JSearch migration-candidate discovery sweep (also runs weekly on a
 * schedule). Returns the ranked net-new employers as JSON — no alerts, no writes.
 */
@Slf4j
@RestController
@RequestMapping("/api/discovery")
public class DiscoveryController {

    private final JSearchDiscoveryService discoveryService;

    public DiscoveryController(JSearchDiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @PostMapping("/jsearch")
    public Mono<DiscoveryReport> jsearch() {
        log.info("JSearch discovery triggered on demand");
        // Off the event loop: the JSearch client blocks, which Reactor forbids on nio threads.
        return Mono.fromCallable(discoveryService::discover)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
