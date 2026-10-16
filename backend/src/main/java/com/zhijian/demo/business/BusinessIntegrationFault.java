package com.zhijian.demo.business;

public class BusinessIntegrationFault extends RuntimeException {
    private final int status;
    private final String code;

    public BusinessIntegrationFault(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
}
