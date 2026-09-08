package com.github.jingyangyu.scmjobnotifier.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.model.JobPosting;
import com.github.jingyangyu.scmjobnotifier.scraper.JobScraper;
import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchClient.DiscoveredJob;
import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchDiscoveryService.DiscoveryReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class JSearchDiscoveryServiceTest {

    private final JSearchClient client = mock(JSearchClient.class);

    private static JobScraper scraper(String platform, String... companies) {
        return new JobScraper() {
            @Override
            public String platform() {
                return platform;
            }

            @Override
            public List<String> companies() {
                return List.of(companies);
            }

            @Override
            public List<JobPosting> scrape(String company) {
                return List.of();
            }
        };
    }

    // direct scraper covers acme/flexport/doordashusa; adzuna "covers" skechers but must be ignored
    private final List<JobScraper> scrapers =
            List.of(
                    scraper("greenhouse", "acme", "flexport", "doordashusa"),
                    scraper("adzuna", "skechers"));

    private static DiscoveredJob j(String emp, String title) {
        return new DiscoveredJob(emp, title, "San Jose", "California", "LinkedIn");
    }

    private JSearchDiscoveryService svc(String queriesRaw) {
        return new JSearchDiscoveryService(client, scrapers, queriesRaw);
    }

    @Test
    void discoverDedupsCoversAndFiltersStaffingThenRanks() {
        when(client.search(anyString()))
                .thenReturn(
                        List.of(
                                j("Acme", "Supply Chain Analyst"), // covered (direct)
                                j("Cynet Systems", "Buyer"), // staffing
                                j("Skechers", "Buyer"), // net-new (adzuna ignored)
                                j("Skechers", "Buyer"), // intra-list dup
                                j("", "X"), // blank employer
                                j("DoorDash", "Planner"), // covered via containment
                                j("Parker Hannifin", "Sourcing Specialist"), // net-new
                                j(
                                        "Parker Hannifin",
                                        "Procurement Specialist"))); // net-new, 2nd role

        DiscoveryReport r = svc("q1;q2").discover();

        assertThat(r.sampled()).isEqualTo(16); // 8 jobs x 2 queries
        assertThat(r.coveredCount()).isEqualTo(2); // Acme + DoorDash (query 1 only; q2 all deduped)
        assertThat(r.staffingCount()).isEqualTo(1); // Cynet
        assertThat(r.candidates()).hasSize(2);
        assertThat(r.candidates().get(0).employer()).isEqualTo("Parker Hannifin");
        assertThat(r.candidates().get(0).roles()).isEqualTo(2);
        assertThat(r.candidates().get(1).employer()).isEqualTo("Skechers");
        assertThat(r.candidates().get(1).roles()).isEqualTo(1);
        assertThat(r.candidates().get(1).sampleTitle()).isEqualTo("Buyer");
        assertThat(r.candidates().get(1).sampleLocation()).isEqualTo("San Jose California");
    }

    @Test
    void scheduledSkipsWhenNotConfigured() {
        when(client.isConfigured()).thenReturn(false);
        svc("q1").scheduledDiscovery();
        verify(client, never()).search(anyString());
    }

    @Test
    void scheduledSkipsWhenNoQueries() {
        when(client.isConfigured()).thenReturn(true);
        svc("   ;  ").scheduledDiscovery(); // parses to zero queries
        verify(client, never()).search(anyString());
    }

    @Test
    void scheduledRunsWhenConfiguredWithQueries() {
        when(client.isConfigured()).thenReturn(true);
        when(client.search(anyString())).thenReturn(List.of(j("Skechers", "Buyer")));
        svc("supply chain in California, USA").scheduledDiscovery();
        verify(client).search(anyString());
    }
}
