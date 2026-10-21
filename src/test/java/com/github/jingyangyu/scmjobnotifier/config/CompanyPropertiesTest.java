package com.github.jingyangyu.scmjobnotifier.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the company-list @ConfigurationProperties: url builders + findByName + getters. */
class CompanyPropertiesTest {

    @Test
    void oracleCloud() {
        OracleCloudProperties.OracleCloudCompany c = new OracleCloudProperties.OracleCloudCompany();
        c.setName("cohu");
        c.setSubdomain("hcbo");
        c.setRegion("us2");
        c.setSiteNumber("CX_1");
        c.setKeywordFiltered(true);
        assertThat(c.getName()).isEqualTo("cohu");
        assertThat(c.getSubdomain()).isEqualTo("hcbo");
        assertThat(c.getRegion()).isEqualTo("us2");
        assertThat(c.getSiteNumber()).isEqualTo("CX_1");
        assertThat(c.isKeywordFiltered()).isTrue();
        assertThat(c.baseUrl()).contains("hcbo").contains("us2").contains("oraclecloud.com");
        assertThat(c.apiUrl(25, 0)).contains("CX_1").contains("25");
        assertThat(c.apiUrl(25, 0, "supply chain")).contains("supply").contains("CX_1");
        assertThat(c.jobUrl("REQ1")).contains("REQ1");

        OracleCloudProperties props = new OracleCloudProperties();
        props.setCompanies(List.of(c));
        assertThat(props.findByName("cohu")).isPresent();
        assertThat(props.findByName("no")).isEmpty();
    }

    @Test
    void successFactors() {
        SuccessFactorsProperties.SuccessFactorsCompany c =
                new SuccessFactorsProperties.SuccessFactorsCompany();
        c.setName("supermicro");
        c.setHost("jobs.supermicro.com");
        assertThat(c.getHost()).isEqualTo("jobs.supermicro.com");
        assertThat(c.searchUrl("supply chain", 10)).contains("jobs.supermicro.com").contains("10");
        assertThat(c.jobUrl("/job/1")).contains("jobs.supermicro.com");

        SuccessFactorsProperties props = new SuccessFactorsProperties();
        props.setCompanies(List.of(c));
        assertThat(props.findByName("supermicro")).isPresent();
        assertThat(props.findByName("x")).isEmpty();
    }

    @Test
    void avature() {
        AvatureProperties.AvatureCompany c = new AvatureProperties.AvatureCompany();
        c.setName("sandisk");
        c.setHost("avature.sandisk.com");
        assertThat(c.getName()).isEqualTo("sandisk");
        assertThat(c.getHost()).isEqualTo("avature.sandisk.com");
        assertThat(c.searchUrl()).isEqualTo("https://avature.sandisk.com/Careers/SearchJobs");
        assertThat(c.listUrl("13")).isEqualTo("https://avature.sandisk.com/13/_portalList");
        assertThat(c.jobUrl("4032"))
                .isEqualTo("https://avature.sandisk.com/Careers/JobDetail/job/4032");

        AvatureProperties props = new AvatureProperties();
        props.setCompanies(List.of(c));
        assertThat(props.getCompanies()).hasSize(1);
        assertThat(props.findByName("sandisk")).isPresent();
        assertThat(props.findByName("nope")).isEmpty();
    }

    @Test
    void radancy() {
        RadancyProperties.RadancyCompany c = new RadancyProperties.RadancyCompany();
        c.setName("boeing");
        c.setHost("jobs.boeing.com");
        assertThat(c.getName()).isEqualTo("boeing");
        assertThat(c.getHost()).isEqualTo("jobs.boeing.com");
        assertThat(c.searchPageUrl()).isEqualTo("https://jobs.boeing.com/search-jobs");
        assertThat(c.searchUrl(2, 100))
                .startsWith("https://jobs.boeing.com/search-jobs/results?")
                .contains("CurrentPage=2")
                .contains("RecordsPerPage=100")
                .contains("SearchResultsModuleName=Search+Results");
        assertThat(c.jobUrl("/job/el-segundo/buyer/185/1"))
                .isEqualTo("https://jobs.boeing.com/job/el-segundo/buyer/185/1");

        RadancyProperties props = new RadancyProperties();
        props.setCompanies(List.of(c));
        assertThat(props.getCompanies()).hasSize(1);
        assertThat(props.findByName("boeing")).isPresent();
        assertThat(props.findByName("nope")).isEmpty();
    }

    @Test
    void icimsCustomDomain() {
        IcimsProperties.IcimsCompany c = new IcimsProperties.IcimsCompany();
        c.setName("x");
        c.setCustomDomain("careers.example.com");
        assertThat(c.baseUrl()).isEqualTo("https://careers.example.com");
    }

    @Test
    void oracleNoRegion() {
        OracleCloudProperties.OracleCloudCompany c = new OracleCloudProperties.OracleCloudCompany();
        c.setSubdomain("sub");
        c.setRegion(null);
        assertThat(c.baseUrl()).isEqualTo("https://sub.fa.oraclecloud.com");
    }

    @Test
    void icims() {
        IcimsProperties.IcimsCompany c = new IcimsProperties.IcimsCompany();
        c.setName("nikkiso");
        c.setSubdomain("careers-nikkiso");
        assertThat(c.baseUrl()).contains("careers-nikkiso").contains("icims.com");
        assertThat(c.searchUrl(1)).contains("/jobs/search").contains("pr=1");
        assertThat(c.jobUrl("55", "buyer")).contains("/jobs/55/buyer/job");

        IcimsProperties props = new IcimsProperties();
        props.setCompanies(List.of(c));
        assertThat(props.findByName("nikkiso")).isPresent();
        assertThat(props.findByName("x")).isEmpty();
    }

    @Test
    void paylocity() {
        PaylocityProperties.PaylocityCompany c = new PaylocityProperties.PaylocityCompany();
        c.setName("baycitiescontainer");
        c.setCompanyId("ABC123");
        c.setSlug("Bay-Cities");
        assertThat(c.getCompanyId()).isEqualTo("ABC123");
        assertThat(c.jobsUrl()).contains("ABC123");
        assertThat(c.jobUrl(42L)).contains("42");

        PaylocityProperties props = new PaylocityProperties();
        props.setCompanies(List.of(c));
        assertThat(props.findByName("baycitiescontainer")).isPresent();
        assertThat(props.findByName("x")).isEmpty();
    }

    @Test
    void brassRing() {
        BrassRingProperties.BrassRingCompany c = new BrassRingProperties.BrassRingCompany();
        c.setName("bestbuy");
        c.setPartnerId("25632");
        c.setSiteId("5310");
        assertThat(c.getPartnerId()).isEqualTo("25632");
        assertThat(c.homeUrl()).contains("25632");

        BrassRingProperties props = new BrassRingProperties();
        props.setCompanies(List.of(c));
        assertThat(props.findByName("bestbuy")).isPresent();
        assertThat(props.findByName("x")).isEmpty();
    }
}
