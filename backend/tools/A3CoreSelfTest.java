package com.zhijian.demo.inspection;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;

/** Executable JDK-only checks, not Spring/MySQL end-to-end tests. */
public class A3CoreSelfTest {
    private static int checks = 0;
    static void ok(boolean condition) { if (!condition) throw new AssertionError("Check " + (checks+1)); checks++; }
    interface Throwing { void run() throws Exception; }
    static void fails(Throwing r) throws Exception { boolean failed = false; try { r.run(); } catch (IllegalArgumentException | java.io.IOException e) { failed = true; } ok(failed); }
    static ReviewRules.Input input(String result, List<ReviewRules.Candidate> list) { return new ReviewRules.Input(0,"tester",result,"人工测试",null,false,list); }
    static ReviewRules.Candidate c(int id, String d, Integer target) { return new ReviewRules.Candidate(id,d,target,""); }
    public static void main(String[] args) throws Exception {
        var pair = List.of(c(1,"confirmed",null),c(2,"duplicate",1));
        ok(ReviewRules.validate(input("target_confirmed",pair),2).candidate_reviews().size()==2);
        ok(ReviewRules.validate(input("needs_recheck",pair),2).conclusion().equals("needs_recheck"));
        ok(ReviewRules.validate(input("no_target_confirmed",List.of()),0).final_grade()==null);
        fails(() -> ReviewRules.validate(null,0));
        fails(() -> ReviewRules.validate(input("target_confirmed",List.of()),0));
        fails(() -> ReviewRules.validate(input("no_target_confirmed",pair),2));
        fails(() -> ReviewRules.validate(input("needs_recheck",List.of(c(1,"",null))),1));
        fails(() -> ReviewRules.validate(input("target_confirmed",List.of(c(1,"uncertain",null))),1));
        fails(() -> ReviewRules.validate(input("needs_recheck",List.of(c(1,"duplicate",1))),1));
        fails(() -> ReviewRules.validate(input("needs_recheck",List.of(c(1,"duplicate",2),c(2,"false_positive",null))),2));
        fails(() -> ReviewRules.validate(input("needs_recheck",List.of(c(1,"confirmed",null),c(1,"confirmed",null))),2));
        fails(() -> ReviewRules.validate(input("needs_recheck",List.of(c(3,"confirmed",null))),1));
        fails(() -> ReviewRules.validate(input("needs_recheck",List.of(c(1,"confirmed",2))),1));
        fails(() -> ReviewRules.validate(new ReviewRules.Input(0,"", "needs_recheck","x",null,false,List.of()),0));
        fails(() -> ReviewRules.validate(new ReviewRules.Input(0,"a", "needs_recheck","",null,false,List.of()),0));
        fails(() -> ReviewRules.validate(new ReviewRules.Input(-1,"a", "needs_recheck","x",null,false,List.of()),0));
        fails(() -> ReviewRules.validate(new ReviewRules.Input(0,"a", "needs_recheck","x","a".repeat(21),false,List.of()),0));
        fails(() -> new AiHttpTransport("file:///tmp",50));
        fails(() -> new AiHttpTransport("http://user:pass@localhost:1",50));
        fails(() -> new AiHttpTransport("http://localhost:1/x",50));
        fails(() -> new AiHttpTransport("http://localhost:1?url=x",50));
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        UUID prediction=UUID.randomUUID();
        server.createContext("/ready", e -> { byte[] b="{\"model_ready\":true}".getBytes(); e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(200,b.length); e.getResponseBody().write(b); e.close(); });
        server.createContext("/ai/inspection/predict", e -> {
            String body=new String(e.getRequestBody().readAllBytes());
            boolean correct=body.contains("name=\"image\"") && body.contains("name=\"batch_id\"\r\n\r\n1") && body.contains("APPLE-2026-001");
            byte[] b=(correct ? "ok" : "bad").getBytes(); e.sendResponseHeaders(correct?200:400,b.length); e.getResponseBody().write(b); e.close();
        });
        server.createContext("/internal/artifacts/"+prediction+"/input", e -> { byte[] b={(byte)137,80,78,71,13,10,26,10,0}; e.getResponseHeaders().set("Content-Type","image/png"); e.sendResponseHeaders(200,b.length); e.getResponseBody().write(b); e.close(); });
        server.createContext("/internal/artifacts/"+prediction+"/result", e -> { byte[] b="not-png".getBytes(); e.getResponseHeaders().set("Content-Type","image/png"); e.sendResponseHeaders(200,b.length); e.getResponseBody().write(b); e.close(); });
        server.start(); Path root=Files.createTempDirectory("a3-http-test");
        try {
            var http=new AiHttpTransport("http://127.0.0.1:"+server.getAddress().getPort(),1000);
            ok(http.ready().status()==200);
            ok(http.predict(1,"APPLE-2026-001",new byte[]{1,2,3},"image/png").status()==200);
            fails(() -> http.predict(1,"bad\r\nheader",new byte[]{1},"image/png"));
            fails(() -> http.predict(1,"GOOD",new byte[]{1},"image/svg+xml"));
            http.downloadPng(prediction,"input",root.resolve("input.png")); ok(Files.size(root.resolve("input.png"))==9);
            fails(() -> http.downloadPng(prediction,"source",root.resolve("source")));
            fails(() -> http.downloadPng(prediction,"result",root.resolve("bad.png"))); ok(!Files.exists(root.resolve("bad.png")));
        } finally { server.stop(0); Files.deleteIfExists(root.resolve("input.png")); Files.deleteIfExists(root); }
        System.out.println("A3_CORE_SELFTEST_PASS checks="+checks);
    }
}
