package com.trs;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
/** 应用启动入口。 @author agent */
@SpringBootApplication
public class Application {
    /** 启动 REST 服务。 @param args 启动参数 @author agent */
    public static void main(String[] args) { SpringApplication.run(Application.class, args); }
}
