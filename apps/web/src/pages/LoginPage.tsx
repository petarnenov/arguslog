import { Alert, Button, Card, Center, Stack, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';

import { useAuth } from '../auth/useAuth';

interface LocationState {
  from?: { pathname?: string };
}

export function LoginPage() {
  const { t } = useTranslation();
  const { signIn, error } = useAuth();
  const location = useLocation();
  const returnTo = (location.state as LocationState | null | undefined)?.from?.pathname;
  const [submitting, setSubmitting] = useState(false);

  const handleSignIn = async () => {
    setSubmitting(true);
    try {
      await signIn(returnTo);
    } catch (e) {
      setSubmitting(false);
      throw e;
    }
  };

  return (
    <Center mih="100vh" p="md">
      <Card shadow="sm" padding="xl" radius="md" withBorder w={420}>
        <Stack>
          <Title order={2}>{t('app.name')}</Title>
          <Text c="dimmed">{t('app.tagline')}</Text>
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Text size="sm">{t('auth.loginHint')}</Text>
          <Button fullWidth size="md" onClick={handleSignIn} loading={submitting}>
            {t('auth.login')}
          </Button>
          <Button fullWidth size="md" variant="subtle" onClick={handleSignIn}>
            {t('auth.signup')}
          </Button>
        </Stack>
      </Card>
    </Center>
  );
}
