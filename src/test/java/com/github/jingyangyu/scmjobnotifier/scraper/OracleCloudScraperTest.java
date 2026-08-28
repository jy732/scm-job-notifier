package com.github.jingyangyu.scmjobnotifier.scraper;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties;
import com.github.jingyangyu.scmjobnotifier.config.OracleCloudProperties.OracleCloudCompany;
import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.support.WebClientStubs;
import java.util.List;
import org.junit.jupiter.api.Test;

class OracleCloudScraperTest {

    private static final String BODY =
            "{\"items\":[{\"TotalJobsCount\":1,\"requisitionList\":[{\"Id\":\"R1\","
                    + "\"Title\":\"Buyer\",\"PrimaryLocation\":\"San Jose, CA\","
                    + "\"PostedDate\":\"2026-08-01\"}]}]}";

    private static OracleCloudProperties props() {
        OracleCloudCompany c = new OracleCloudCompany();
        c.setName("cohu");
        c.setSubdomain("hcbo");
        c.setRegion("us2");
        c.setSiteNumber("CX_1");
        c.setKeywordFiltered(false);
        OracleCloudProperties p = new OracleCloudProperties();
        p.setCompanies(List.of(c));
        return p;
    }

    @Test
    void platformAndCompanies() {
        OracleCloudScraper s = new OracleCloudScraper(WebClientStubs.json(u -> BODY), props());
        assertThat(s.platform()).isEqualTo("oraclecloud");
        assertThat(s.companies()).containsExactly("cohu");
    }

    @Test
    void scrapeParsesRequisitions() {
        OracleCloudScraper s = new OracleCloudScraper(WebClientStubs.json(u -> BODY), props());
        List<JobPosting> jobs = s.scrape("cohu");
        assertThat(jobs).hasSize(1);
        JobPosting j = jobs.get(0);
        assertThat(j.getExternalId()).isEqualTo("R1");
        assertThat(j.getTitle()).isEqualTo("Buyer");
        assertThat(j.getLocation()).isEqualTo("San Jose, CA");
    }

    @Test
    void unknownCompanyEmpty() {
        OracleCloudScraper s = new OracleCloudScraper(WebClientStubs.json(u -> BODY), props());
        assertThat(s.scrape("nope")).isEmpty();
    }

    @Test
    void keywordFilteredModeRunsQueriesAndDedupes() {
        OracleCloudCompany c = new OracleCloudCompany();
        c.setName("albertsons");
        c.setSubdomain("eofd");
        c.setRegion("us6");
        c.setSiteNumber("CX_1001");
        c.setKeywordFiltered(true);
        OracleCloudProperties p = new OracleCloudProperties();
        p.setCompanies(List.of(c));
        OracleCloudScraper s = new OracleCloudScraper(WebClientStubs.json(u -> BODY), p);
        // every SCM query returns the same requisition → deduped to one by Id
        assertThat(s.scrape("albertsons")).hasSize(1);
    }
}
