package org.arguslog.api.adapter.in.web;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.arguslog.api.domain.Platform;
import org.arguslog.api.testsupport.AbstractControllerTest;
import org.junit.jupiter.api.Test;

class PlatformControllerTest extends AbstractControllerTest {

  @Test
  void listReturnsCatalogInOrder() throws Exception {
    when(platformUseCase.list())
        .thenReturn(
            List.of(
                new Platform(
                    "javascript", "JavaScript / Browser", "@arguslog/sdk-browser", "1.0.0", 10),
                new Platform("react", "React", "@arguslog/sdk-react", "1.0.0", 20)));

    mvc.perform(get("/api/v1/platforms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(2)))
        .andExpect(jsonPath("$[0].slug").value("javascript"))
        .andExpect(jsonPath("$[0].name").value("JavaScript / Browser"))
        .andExpect(jsonPath("$[0].sdkPackage").value("@arguslog/sdk-browser"))
        .andExpect(jsonPath("$[0].sdkVersion").value("1.0.0"))
        .andExpect(jsonPath("$[1].slug").value("react"));
  }

  @Test
  void emptyCatalogReturnsEmptyArray() throws Exception {
    when(platformUseCase.list()).thenReturn(List.of());
    mvc.perform(get("/api/v1/platforms"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", hasSize(0)));
  }
}
