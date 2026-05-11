package org.arguslog.worker.adapter.out.crypto;

import org.arguslog.crypto.AesGcmSecretCipher;
import org.arguslog.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@link AesGcmSecretCipher} (in {@code :lib:crypto-aes-gcm}) as the worker's
 * {@link SecretCipher} bean. Same env var ({@code arguslog.alerts.secret-key}) as api — a single
 * master key serves both services. Worker only ever decrypts; encryption stays in api.
 */
@Configuration
class CipherConfig {

  @Bean
  SecretCipher secretCipher(@Value("${arguslog.alerts.secret-key:}") String base64Key) {
    return new AesGcmSecretCipher(base64Key);
  }
}
