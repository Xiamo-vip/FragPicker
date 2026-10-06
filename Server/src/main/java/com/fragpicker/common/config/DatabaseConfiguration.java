package com.fragpicker.common.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("database")
@MapperScan(basePackages = "com.fragpicker", annotationClass = Mapper.class)
public class DatabaseConfiguration {
}
