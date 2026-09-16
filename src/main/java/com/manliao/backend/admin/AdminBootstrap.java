package com.manliao.backend.admin;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import jakarta.validation.Validator;
@Configuration
@ConditionalOnProperty(name="app.admin.bootstrap-enabled",havingValue="true")
public class AdminBootstrap {
 @Bean ApplicationRunner bootstrap(AdminService admin,Validator validator,
   @Value("${app.admin.bootstrap-username:}") String username,@Value("${app.admin.bootstrap-password:}") String password) {
   return args->{
     var input=new AdminDtos.Create(username,password,"本地开发管理员","owner");
     if(!validator.validate(input).isEmpty())throw new IllegalStateException("Explicit valid administrator bootstrap credentials required");
     admin.bootstrap(input);
   };
 }
}
