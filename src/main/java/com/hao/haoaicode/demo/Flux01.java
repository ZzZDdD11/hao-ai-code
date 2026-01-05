package com.hao.haoaicode.demo;

import lombok.extern.slf4j.Slf4j;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Flux;
@Slf4j
public class Flux01 {

    /**
     * 练习 1：最基础的 Flux 创建与遍历
     * 目标：熟悉 Flux.just / Flux.fromIterable / subscribe。
     *
     * 内容：
     * 创建一个 Flux<String>，包含 "a", "b", "c"。
     * 用 subscribe(System.out::println) 打印每个元素。
     * 用 doOnNext 加一行日志，比如打印当前线程名
     */
    public static void main(String[] args) {

        // 创建flux
        Flux<String> flux1 = Flux.just("a", "b", "c");

        flux1.doOnNext(value -> log.info("当前线程为: {}, 元素为: {}", Thread.currentThread().getName(), value))
            .subscribe(System.out :: println);
    }

}
