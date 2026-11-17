package io.github.kmadsdev.consignado.api;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.kmadsdev.consignado.application.CustomerService;
import io.github.kmadsdev.consignado.application.ResourceNotFoundException;
import io.github.kmadsdev.consignado.application.Views.CustomerView;
import io.github.kmadsdev.consignado.application.Views.PageView;
import io.github.kmadsdev.consignado.domain.IncomeBelowCommittedMarginException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
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

    @Test
    void incomeChangeReturnsTheCustomer() throws Exception {
        CustomerView view = customer("1234.56");
        given(service.changeIncome(view.id(), new BigDecimal("1234.56"))).willReturn(view);

        mvc.perform(patch("/api/v1/customers/{id}/income", view.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyIncome\": 1234.56}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(view.id().toString()))
                .andExpect(jsonPath("$.netMonthlySalary").value(1234.56));
    }

    @ParameterizedTest(name = "body {0}")
    @ValueSource(strings = {
            "{}",
            "{\"monthlyIncome\": null}",
            "{\"monthlyIncome\": 0}",
            "{\"monthlyIncome\": -1}",
            "{\"monthlyIncome\": 1234.567}",
            "{\"monthlyIncome\": 12345678901234.00}",
    })
    void anInvalidIncomeIsA400ThatNamesTheField(String body) throws Exception {
        mvc.perform(patch("/api/v1/customers/{id}/income", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation-failed"))
                .andExpect(jsonPath("$.errors[0].field").value("monthlyIncome"));
        verifyNoInteractions(service);
    }

    @Test
    void anIncomeBelowTheCommittedMarginIs422WithTheNumbers() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.changeIncome(id, new BigDecimal("1000.00"))).willThrow(
                new IncomeBelowCommittedMarginException(new BigDecimal("350.00"), new BigDecimal("916.80")));

        mvc.perform(patch("/api/v1/customers/{id}/income", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyIncome\": 1000.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("urn:consignado:problem:income-below-committed-margin"))
                .andExpect(jsonPath("$.code").value("income-below-committed-margin"))
                .andExpect(jsonPath("$.marginLimit").value(350.00))
                .andExpect(jsonPath("$.committedInstallments").value(916.80));
    }

    @Test
    void incomeChangeOfAnUnknownCustomerIs404() throws Exception {
        UUID id = UUID.randomUUID();
        given(service.changeIncome(id, new BigDecimal("1000.00")))
                .willThrow(new ResourceNotFoundException("Customer", id));

        mvc.perform(patch("/api/v1/customers/{id}/income", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyIncome\": 1000.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not-found"));
    }

    @Test
    void customersCannotBeDeletedOrReplaced() throws Exception {
        UUID id = UUID.randomUUID();

        mvc.perform(delete("/api/v1/customers/{id}", id)).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/v1/customers/{id}", id).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(service);
    }

    static CustomerView customer(String salary) {
        return new CustomerView(UUID.randomUUID(), "***.***.***-09", "Test Customer", UUID.randomUUID(),
                new BigDecimal(salary), Instant.parse("2026-03-01T12:00:00Z"));
    }
}
