import { Center, Loader, Stack, Text } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { getUserManager } from '../auth/userManager';

interface OidcReturnState {
  returnTo?: string;
}

/**
 * The redirect target Keycloak sends the browser back to after the user signs in. Calls
 * signinRedirectCallback to exchange the auth code for tokens, hydrates the auth store via
 * the AuthProvider's userLoaded subscription, then navigates to the {@code returnTo} URL the
 * caller stashed (or /orgs as the dashboard's home).
 */
export function AuthCallbackPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getUserManager()
      .signinRedirectCallback()
      .then((user) => {
        if (cancelled) return;
        const returnTo = (user.state as OidcReturnState | null | undefined)?.returnTo ?? '/orgs';
        navigate(returnTo, { replace: true });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof Error ? err.message : 'callback failed');
      });
    return () => {
      cancelled = true;
    };
  }, [navigate]);

  if (error) {
    return (
      <Center mih="100vh" p="md">
        <Stack align="center">
          <Text c="red">{t('auth.callbackFailed')}</Text>
          <Text size="sm" c="dimmed">
            {error}
          </Text>
        </Stack>
      </Center>
    );
  }
  return (
    <Center mih="100vh">
      <Stack align="center">
        <Loader size="md" />
        <Text c="dimmed">{t('auth.signingIn')}</Text>
      </Stack>
    </Center>
  );
}
