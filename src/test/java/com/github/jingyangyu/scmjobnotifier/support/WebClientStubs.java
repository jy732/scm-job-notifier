package com.github.jingyangyu.scmjobnotifier.support;

import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Test support: {@link WebClient.Builder}s backed by a stub {@link ExchangeFunction}. */
public final class WebClientStubs {

    private WebClientStubs() {}

    /** Builder whose every exchange returns a 200 JSON body chosen by the request URL. */
    public static WebClient.Builder json(Function<String, String> urlToBody) {
        ExchangeFunction ex =
                req ->
                        Mono.just(
                                ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                                        .body(urlToBody.apply(req.url().toString()))
                                        .build());
        return WebClient.builder().exchangeFunction(ex);
    }

    /** Builder whose every exchange returns a 200 body with the given content type. */
    public static WebClient.Builder text(Function<String, String> urlToBody, String contentType) {
        ExchangeFunction ex =
                req ->
                        Mono.just(
                                ClientResponse.create(HttpStatus.OK)
                                        .header("Content-Type", contentType)
                                        .body(urlToBody.apply(req.url().toString()))
                                        .build());
        return WebClient.builder().exchangeFunction(ex);
    }

    /** Builder whose exchanges always error (exercises scraper catch/empty-list paths). */
    public static WebClient.Builder erroring() {
        ExchangeFunction ex = req -> Mono.error(new RuntimeException("stub network error"));
        return WebClient.builder().exchangeFunction(ex);
    }
}
