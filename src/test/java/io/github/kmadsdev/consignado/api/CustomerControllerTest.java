package io.github.kmadsdev.consignado.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kmadsdev.consignado.application.CustomerService;
import io.github.kmadsdev.consignado.application.Views.CustomerView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Web slice: HTTP contract of the controller with the use case mocked. */
@WebMvcTest(CustomerController.class)
class CustomerControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CustomerService service;

    @Test
    void listReturnsThePageEnvelopeWithTheDefaultPaging() throws Exception {
        given(service.list(0, 20)).willReturn(new PageView<>(List.of(customer("5000.00")), 0, 20, 1, 1));

        mvc.perform(get("/api/v1/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].cpf").value("***.***.***-09"))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void pageSizeIsCappedAt100() throws Exception {
        mvc.perform(get("/api/v1/customers").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"));
        verifyNoInteractions(service);
    }

    @Test
    void aNegativePageIsRejected() throws Exception {
        mvc.perform(get("/api/v1/customers").param("page", "-1"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    static CustomerView customer(String salary) {
        return new CustomerView(UUID.randomUUID(), "***.***.***-09", "Test Customer", UUID.randomUUID(),
                new BigDecimal(salary), Instant.parse("2026-03-01T12:00:00Z"));
    }
}
