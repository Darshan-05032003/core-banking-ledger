package com.darshan.ledger.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.darshan.ledger.security.Role;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AppUserController.class)
@WithMockUser(roles = "ADMIN")
class AppUserControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserService appUserService;

    @Test
    void creatingAUserReturns201WithItsLocationAndNeverEchoesThePassword() throws Exception {
        given(appUserService.create(any())).willReturn(new AppUserResponse(5L, "teller1", Set.of(Role.TELLER), null,
                true, "admin", Instant.parse("2026-10-22T10:15:30Z")));

        mockMvc.perform(post("/api/v1/admin/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teller1\",\"password\":\"correct-horse-battery\",\"roles\":[\"TELLER\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/v1/admin/users/5")))
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.username").value("teller1"))
                .andExpect(jsonPath("$.roles[0]").value("TELLER"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(content().string(not(containsString("correct-horse-battery"))))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void aShortPasswordAnUnknownRoleOrAnEmptyRoleSetIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"t!\",\"password\":\"short\",\"roles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'username')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'roles')]").exists());
        mockMvc.perform(post("/api/v1/admin/users").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"teller1\",\"password\":\"correct-horse-battery\",\"roles\":[\"ROOT\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
        verifyNoInteractions(appUserService);
    }

    @Test
    void linkingRequiresACustomerId() throws Exception {
        mockMvc.perform(put("/api/v1/admin/users/5/customer").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("customerId"));
        verifyNoInteractions(appUserService);
    }
}
