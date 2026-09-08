package com.github.jingyangyu.scmjobnotifier.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchDiscoveryService;
import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchDiscoveryService.Candidate;
import com.github.jingyangyu.scmjobnotifier.service.discovery.JSearchDiscoveryService.DiscoveryReport;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiscoveryControllerTest {

    private final JSearchDiscoveryService service = mock(JSearchDiscoveryService.class);
    private final DiscoveryController controller = new DiscoveryController(service);

    @Test
    void jsearchEndpointReturnsReport() {
        DiscoveryReport report =
                new DiscoveryReport(
                        20, 8, 1, List.of(new Candidate("Skechers", 2, "Buyer", "San Jose CA")));
        when(service.discover()).thenReturn(report);

        DiscoveryReport r = controller.jsearch().block();

        assertThat(r).isNotNull();
        assertThat(r.candidates()).hasSize(1);
        assertThat(r.candidates().get(0).employer()).isEqualTo("Skechers");
        verify(service).discover();
    }
}
