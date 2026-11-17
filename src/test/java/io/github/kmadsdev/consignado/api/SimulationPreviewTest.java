package io.github.kmadsdev.consignado.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kmadsdev.consignado.application.SimulationPreviewService;
import io.github.kmadsdev.consignado.application.SimulationService;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.config.ClockConfig;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.BDDMockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.repository.Repository;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The guest preview, with the REAL calculation and no database at all. A web slice has no
 * DataSource, no JPA and no repository, and the only stateful use case is a mock that must
 * stay untouched. If the preview ever needed storage, this context would not even start.
 */
@WebMvcTest(SimulationController.class)
@Import({SimulationPreviewService.class, ClockConfig.class})
class SimulationPreviewTest {

    private static final String PATH = "/api/v1/simulations/preview";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @MockitoBean
    private SimulationService storedSimulations;

    @Test
    void theContextHasNoRepositoryNoDataSourceAndNoTransactionManager() {
        assertThat(context.getBeanNamesForType(Repository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class)).isEmpty();
    }

    @Test
    void previewsALoanWithoutTouchingTheStoredSimulations() throws Exception {
        preview("{\"principal\": 10000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.principal").value(10000.00))
                .andExpect(jsonPath("$.termMonths").value(12))
                .andExpect(jsonPath("$.monthlyInterestRate").value(0.015))
                .andExpect(jsonPath("$.installment").value(916.80))
                .andExpect(jsonPath("$.totalAmount").value(11001.61))
                .andExpect(jsonPath("$.totalInterest").value(1001.61))
                .andExpect(jsonPath("$.marginLimit").doesNotExist())
                .andExpect(jsonPath("$.withinMargin").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
        verifyNoInteractions(storedSimulations);
    }

    @Test
    void withIncomeAndMarginPercentItAddsTheMarginCheck() throws Exception {
        preview("""
                {"principal": 10000.00, "termMonths": 12, "monthlyInterestRate": 0.015,
                 "monthlyIncome": 5000.00, "maxMarginPercent": 35}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginLimit").value(1750.00))
                .andExpect(jsonPath("$.withinMargin").value(true));

        preview("""
                {"principal": 10000.00, "termMonths": 12, "monthlyInterestRate": 0.015,
                 "monthlyIncome": 1000.00, "maxMarginPercent": 35}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marginLimit").value(350.00))
                .andExpect(jsonPath("$.withinMargin").value(false));
        verifyNoInteractions(storedSimulations);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "principal|{\"termMonths\": 12, \"monthlyInterestRate\": 0.015}",
            "principal|{\"principal\": 99.99, \"termMonths\": 12, \"monthlyInterestRate\": 0.015}",
            "principal|{\"principal\": 1000000.01, \"termMonths\": 12, \"monthlyInterestRate\": 0.015}",
            "principal|{\"principal\": 1000.001, \"termMonths\": 12, \"monthlyInterestRate\": 0.015}",
            "termMonths|{\"principal\": 1000.00, \"monthlyInterestRate\": 0.015}",
            "termMonths|{\"principal\": 1000.00, \"termMonths\": 0, \"monthlyInterestRate\": 0.015}",
            "termMonths|{\"principal\": 1000.00, \"termMonths\": 421, \"monthlyInterestRate\": 0.015}",
            "monthlyInterestRate|{\"principal\": 1000.00, \"termMonths\": 12}",
            "monthlyInterestRate|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": -0.01}",
            "monthlyInterestRate|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 1}",
            "monthlyInterestRate|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.0000001}",
            "monthlyIncome|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015,"
                    + " \"monthlyIncome\": 0, \"maxMarginPercent\": 35}",
            "maxMarginPercent|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015,"
                    + " \"monthlyIncome\": 5000, \"maxMarginPercent\": 100.01}",
            "maxMarginPercent|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015,"
                    + " \"monthlyIncome\": 5000, \"maxMarginPercent\": 0}",
            "marginInputComplete|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015,"
                    + " \"monthlyIncome\": 5000}",
            "marginInputComplete|{\"principal\": 1000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015,"
                    + " \"maxMarginPercent\": 35}",
    })
    void inputsOutsideTheBoundsAre400AndNameTheField(String fieldAndBody) throws Exception {
        String field = fieldAndBody.substring(0, fieldAndBody.indexOf('|'));
        String body = fieldAndBody.substring(fieldAndBody.indexOf('|') + 1);

        preview(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    @Test
    void aTermThatPaysTheLoanOffEarlyIs422() throws Exception {
        preview("{\"principal\": 100.00, \"termMonths\": 420, \"monthlyInterestRate\": 0}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("term-too-long-for-amount"));
    }

    @Test
    void simulationsListReturnsThePageEnvelopeAndCapsThePageSize() throws Exception {
        BDDMockito.given(storedSimulations.list(0, 20)).willReturn(new PageView<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/v1/simulations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/simulations").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theTimingHeadersSayNoStatementRan() throws Exception {
        preview("{\"principal\": 10000.00, \"termMonths\": 12, \"monthlyInterestRate\": 0.015}")
                .andExpect(status().isOk())
                .andExpect(header().string("Server-Timing", matchesPattern("app;dur=\\d+\\.\\d")))
                .andExpect(header().string("X-Consignado-Timing",
                        matchesPattern("\\{\"instance\":\"[A-Za-z0-9._-]+\",\"appMs\":\\d+\\.\\d,\"db\":null}")));
    }

    private ResultActions preview(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
