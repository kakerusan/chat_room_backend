package fun.hatsumi.chatbackend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan({
        "fun.hatsumi.chatbackend.user.mapper",
        "fun.hatsumi.chatbackend.chat.mapper",
        "fun.hatsumi.chatbackend.file.mapper",
        "fun.hatsumi.chatbackend.network.mapper"
})
@EnableScheduling
public class ChatBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatBackendApplication.class, args);
    }

}
