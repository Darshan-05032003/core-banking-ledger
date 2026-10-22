package com.darshan.ledger.customer;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.common.error.BusinessRuleException;
import com.darshan.ledger.common.error.ConflictException;
import com.darshan.ledger.common.error.ResourceNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CustomerController.class)
@WithMockUser(roles = "TELLER")
class CustomerControllerTests {

    private static final String VALID_BODY = """
            {"fullName":"Asha Rao","email":"asha@example.com","phone":"+919876543210","dateOfBirth":"1990-05-17"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    @Test
    void onboardingReturns201WithLocationAndMaskedPersonalData() throws Exception {
        given(customerService.onboard(any())).willReturn(response(KycStatus.PENDING, null));

        mockMvc.perform(post("/api/v1/customers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/customers/7")))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.maskedEmail").value("a***@example.com"))
                .andExpect(jsonPath("$.maskedPhone").value("XXXXXXXXX3210"))
                .andExpect(jsonPath("$.kycStatus").value("PENDING"))
                .andExpect(content().string(not(containsString("asha@example.com"))))
                .andExpect(content().string(not(containsString("+919876543210"))));
    }

    @Test
    void onboardingValidatesEveryField() throws Exception {
        mockMvc.perform(post("/api/v1/customers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":" ","email":"not-an-email","phone":"12-34","dateOfBirth":"2999-01-01"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'phone')].message")
                        .value("must be 10 to 15 digits with an optional leading +"))
                .andExpect(jsonPath("$.errors[?(@.field == 'dateOfBirth')]").exists());

        verifyNoInteractions(customerService);
    }

    @Test
    void underageCustomerIsRejectedWithABusinessErrorCode() throws Exception {
        given(customerService.onboard(any())).willThrow(new BusinessRuleException(
                CustomerErrorCode.CUSTOMER_UNDERAGE, "Customer must be at least 18 years old"));

        mockMvc.perform(post("/api/v1/customers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("CUSTOMER_UNDERAGE"));
    }

    @Test
    void duplicateEmailIsAConflict() throws Exception {
        given(customerService.onboard(any())).willThrow(new ConflictException(
                CustomerErrorCode.EMAIL_ALREADY_REGISTERED, "A customer with this email is already registered"));

        mockMvc.perform(post("/api/v1/customers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void unknownCustomerIs404() throws Exception {
        given(customerService.get(99L)).willThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(get("/api/v1/customers/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void kycVerificationReturnsTheUpdatedCustomer() throws Exception {
        given(customerService.verifyKyc(7L)).willReturn(response(KycStatus.VERIFIED, null));

        mockMvc.perform(post("/api/v1/customers/7/kyc/verify").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andExpect(jsonPath("$.kycReason").doesNotExist());
    }

    @Test
    void kycRejectionRequiresAReason() throws Exception {
        mockMvc.perform(post("/api/v1/customers/7/kyc/reject").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        verifyNoInteractions(customerService);
    }

    @Test
    void kycRejectionReturnsTheReason() throws Exception {
        given(customerService.rejectKyc(eq(7L), any())).willReturn(response(KycStatus.REJECTED, "Document expired"));

        mockMvc.perform(post("/api/v1/customers/7/kyc/reject").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Document expired\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("REJECTED"))
                .andExpect(jsonPath("$.kycReason").value("Document expired"));
    }

    @Test
    void repeatedKycDecisionIsAConflict() throws Exception {
        given(customerService.verifyKyc(7L)).willThrow(new ConflictException(
                CustomerErrorCode.KYC_STATUS_UNCHANGED, "KYC status of customer 7 is already VERIFIED"));

        mockMvc.perform(post("/api/v1/customers/7/kyc/verify").with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("KYC_STATUS_UNCHANGED"));
    }

    private static CustomerResponse response(KycStatus status, String reason) {
        return new CustomerResponse(7L, "Asha Rao", "a***@example.com", "XXXXXXXXX3210",
                LocalDate.of(1990, 5, 17), status, reason, null, Instant.parse("2026-10-22T10:15:30Z"));
    }
}
