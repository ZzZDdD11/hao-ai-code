package com.hao.haoaicode.model.context;

public class GenerationContextHolder {
    private static final ThreadLocal<GenerationContext> contextHolder = new ThreadLocal<>();

    public static void setContext(GenerationContext context) {
        contextHolder.set(context);
    }

    public static GenerationContext getContext() {
        return contextHolder.get();
    }

    public static void clearContext() {
        contextHolder.remove();
    }
}
