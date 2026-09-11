package co.yixiang.yshop.module.minipay.service;

import org.springframework.http.HttpStatus;

public final class MiniPayProblem extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public MiniPayProblem(String code, HttpStatus status) {
        super(code);
        this.code = code;
        this.status = status;
    }

    public String code() { return code; }
    public HttpStatus status() { return status; }
}
