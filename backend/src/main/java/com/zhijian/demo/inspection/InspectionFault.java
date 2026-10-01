package com.zhijian.demo.inspection;

public class InspectionFault extends RuntimeException {
    public final int status;
    public final String code;
    public InspectionFault(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
}
