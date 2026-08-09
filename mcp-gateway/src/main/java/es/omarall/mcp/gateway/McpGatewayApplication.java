package es.omarall.mcp.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
@MapperScan("es.omarall.mcp.gateway.mapper")
public class McpGatewayApplication {
    static void main(String[] args) {
        SpringApplication.run(McpGatewayApplication.class, args);
    }
}
