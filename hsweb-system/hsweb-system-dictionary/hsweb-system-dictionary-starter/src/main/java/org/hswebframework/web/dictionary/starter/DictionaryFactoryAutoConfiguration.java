package org.hswebframework.web.dictionary.starter;

import org.hswebframework.web.dict.DictDefineRepository;
import org.hswebframework.web.dict.DictSupportApi;
import org.hswebframework.web.dict.defaults.DefaultDictSupportApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * @author zhouhao
 */
@Configuration
@ComponentScan({"org.hswebframework.web.dictionary.simple"
        , "org.hswebframework.web.controller.dictionary"})
public class DictionaryFactoryAutoConfiguration {

    @Bean
    public DictSupportApi dictSupportApi(DictDefineRepository repository) {
        return new DefaultDictSupportApi(repository);
    }
}
