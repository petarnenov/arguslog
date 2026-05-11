package org.arguslog.api.alerts.adapter.out.crypto;

import org.arguslog.crypto.AesGcmSecretCipher;
import org.arguslog.crypto.SecretCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared {@link AesGcmSecretCipher} (in {@code :lib:crypto-aes-gcm}) as the api's
 * {@link SecretCipher} bean. Reads {@code arguslog.alerts.secret-key} as a base64-encoded
 * 32-byte AES-256 master key. An empty value falls back to a built-in dev key with a loud WARN
 * at boot — DO NOT run prod that way.
 */
@Configuration
class CipherConfig {

  @Bean
  SecretCipher secretCipher(@Value("${arguslog.alerts.secret-key:}") String base64Key) {
    return new AesGcmSecretCipher(base64Key);
  }
}
