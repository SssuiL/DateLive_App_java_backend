package com.manliao.backend.media;
import java.io.IOException;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.*;
@Configuration
public class MediaConfiguration {
 @Bean MultipartConfigElement multipartConfigElement(MediaStorage storage)throws IOException {
   return new MultipartConfigElement(storage.tempDirectory().toString(),32*1024*1024,33*1024*1024,0);
 }
}
