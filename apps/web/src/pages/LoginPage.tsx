import { Button, Card, Center, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';

export function LoginPage() {
  const { t } = useTranslation();
  return (
    <Center mih="100vh" p="md">
      <Card shadow="sm" padding="xl" radius="md" withBorder w={420}>
        <Stack>
          <Title order={2}>{t('app.name')}</Title>
          <Text c="dimmed">{t('app.tagline')}</Text>
          <Text size="sm">{t('auth.loginHint')}</Text>
          <Button fullWidth size="md">
            {t('auth.login')}
          </Button>
          <Button fullWidth size="md" variant="subtle">
            {t('auth.signup')}
          </Button>
        </Stack>
      </Card>
    </Center>
  );
}
