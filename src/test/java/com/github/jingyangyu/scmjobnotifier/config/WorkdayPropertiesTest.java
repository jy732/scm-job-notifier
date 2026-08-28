package com.github.jingyangyu.scmjobnotifier.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.WorkdayProperties.WorkdayCompany;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkdayPropertiesTest {

    private static WorkdayCompany company() {
        WorkdayCompany c = new WorkdayCompany();
        c.setName("iherb");
        c.setSubdomain("iherb");
        c.setInstance(5);
        c.setSite("Careers");
        return c;
    }

    @Test
    void urlsBuiltFromParams() {
        WorkdayCompany c = company();
        assertThat(c.baseUrl()).isEqualTo("https://iherb.wd5.myworkdayjobs.com");
        assertThat(c.apiUrl())
                .isEqualTo("https://iherb.wd5.myworkdayjobs.com/wday/cxs/iherb/Careers/jobs");
        assertThat(c.jobUrl("/job/123"))
                .isEqualTo("https://iherb.wd5.myworkdayjobs.com/en-US/Careers/job/123");
    }

    @Test
    void gettersReflectSetters() {
        WorkdayCompany c = company();
        assertThat(c.getName()).isEqualTo("iherb");
        assertThat(c.getSubdomain()).isEqualTo("iherb");
        assertThat(c.getInstance()).isEqualTo(5);
        assertThat(c.getSite()).isEqualTo("Careers");
    }

    @Test
    void findByName() {
        WorkdayProperties props = new WorkdayProperties();
        props.setCompanies(List.of(company()));
        assertThat(props.getCompanies()).hasSize(1);
        assertThat(props.findByName("iherb")).map(WorkdayCompany::getSubdomain).contains("iherb");
        assertThat(props.findByName("nope")).isEqualTo(Optional.empty());
    }
}
