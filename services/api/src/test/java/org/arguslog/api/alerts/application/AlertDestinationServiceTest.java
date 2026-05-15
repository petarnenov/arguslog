package org.arguslog.api.alerts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Optional;
import org.arguslog.api.alerts.application.AlertDestinationUseCase.InvalidDestinationConfigException;
import org.arguslog.api.alerts.application.port.AlertDestinationRepository;
import org.arguslog.api.alerts.domain.AlertDestination;
import org.arguslog.api.alerts.domain.DestinationKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AlertDestinationServiceTest {

  @Mock AlertDestinationRepository repository;
  @Mock org.arguslog.api.alerts.application.port.AlertDestinationWriteRepository writes;

  AlertDestinationService service;
  ObjectMapper mapper;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    service = new AlertDestinationService(repository, writes, mapper);
  }

  @Test
  void createsTelegramDestinationWithChatIdAndBotToken() {
    ObjectNode config = mapper.createObjectNode().put("chatId", "-100").put("botToken", "abc:123");
    when(writes.create(eq(1L), eq(DestinationKind.TELEGRAM), eq("ops"), anyString()))
        .thenReturn(sample(DestinationKind.TELEGRAM, "ops"));

    AlertDestination created = service.create(1L, DestinationKind.TELEGRAM, "ops", config);

    assertThat(created.kind()).isEqualTo(DestinationKind.TELEGRAM);
    verify(writes).create(eq(1L), eq(DestinationKind.TELEGRAM), eq("ops"), anyString());
  }

  @Test
  void rejectsTelegramMissingBotToken() {
    ObjectNode config = mapper.createObjectNode().put("chatId", "-100");
    assertThatThrownBy(() -> service.create(1L, DestinationKind.TELEGRAM, "ops", config))
        .isInstanceOf(InvalidDestinationConfigException.class)
        .hasMessageContaining("botToken");
    verify(writes, never()).create(anyLong(), any(), any(), any());
  }

  @Test
  void rejectsEmailWithEmptyToArray() {
    ObjectNode config = mapper.createObjectNode();
    config.putArray("to"); // empty
    assertThatThrownBy(() -> service.create(1L, DestinationKind.EMAIL, "all", config))
        .isInstanceOf(InvalidDestinationConfigException.class)
        .hasMessageContaining("to");
  }

  @Test
  void acceptsEmailWithRecipients() {
    ObjectNode config = mapper.createObjectNode();
    config.putArray("to").add("alice@example.com");
    when(writes.create(eq(1L), eq(DestinationKind.EMAIL), eq("alice"), anyString()))
        .thenReturn(sample(DestinationKind.EMAIL, "alice"));
    service.create(1L, DestinationKind.EMAIL, "alice", config);
    verify(writes).create(eq(1L), eq(DestinationKind.EMAIL), eq("alice"), anyString());
  }

  @Test
  void rejectsSlackMissingWebhookUrl() {
    ObjectNode config = mapper.createObjectNode();
    assertThatThrownBy(() -> service.create(1L, DestinationKind.SLACK, "x", config))
        .isInstanceOf(InvalidDestinationConfigException.class)
        .hasMessageContaining("webhookUrl");
  }

  @Test
  void rejectsBlankName() {
    ObjectNode config = mapper.createObjectNode();
    config.putArray("to").add("a@b.co");
    assertThatThrownBy(() -> service.create(1L, DestinationKind.EMAIL, "  ", config))
        .isInstanceOf(InvalidDestinationConfigException.class)
        .hasMessageContaining("name");
  }

  @Test
  void rejectsNonObjectConfig() {
    var arrayConfig = mapper.createArrayNode();
    assertThatThrownBy(() -> service.create(1L, DestinationKind.WEBHOOK, "x", arrayConfig))
        .isInstanceOf(InvalidDestinationConfigException.class)
        .hasMessageContaining("JSON object");
  }

  @Test
  void updateReturnsEmptyForUnknownDestination() {
    ObjectNode config = mapper.createObjectNode().put("url", "https://x");
    when(repository.find(1L, 99L)).thenReturn(Optional.empty());

    Optional<AlertDestination> result = service.update(1L, 99L, "x", config);

    assertThat(result).isEmpty();
    verify(writes, never()).update(anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void updateValidatesAgainstStoredKindNotRequestedKind() {
    // The kind is fixed at create time; update only changes name + config but the validation rule
    // must use the stored kind (so a Telegram destination keeps requiring botToken).
    ObjectNode brokenConfig = mapper.createObjectNode().put("chatId", "-100"); // missing botToken
    when(repository.find(1L, 7L)).thenReturn(Optional.of(sample(DestinationKind.TELEGRAM, "ops")));

    assertThatThrownBy(() -> service.update(1L, 7L, "ops", brokenConfig))
        .isInstanceOf(InvalidDestinationConfigException.class)
        .hasMessageContaining("botToken");
  }

  @Test
  void updateWithNullConfigKeepsExistingConfigJson() {
    // Renaming alone shouldn't force a re-paste of the secret config — the service must reuse
    // the stored configJson when the caller passes null.
    AlertDestination existing = sample(DestinationKind.SLACK, "old-name");
    when(repository.find(1L, 7L)).thenReturn(Optional.of(existing));
    when(writes.update(eq(1L), eq(7L), eq("new-name"), eq(existing.configJson())))
        .thenReturn(Optional.of(sample(DestinationKind.SLACK, "new-name")));

    Optional<AlertDestination> result = service.update(1L, 7L, "new-name", null);

    assertThat(result).isPresent();
    verify(writes).update(1L, 7L, "new-name", existing.configJson());
  }

  @Test
  void deletePassesThrough() {
    when(writes.delete(1L, 7L)).thenReturn(true);
    assertThat(service.delete(1L, 7L)).isTrue();
  }

  private static AlertDestination sample(DestinationKind kind, String name) {
    return new AlertDestination(7L, 1L, kind, name, "{}", Instant.parse("2026-05-05T12:00:00Z"));
  }
}
