import { Button, Card, Center, Select, Stack, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useTranslation } from 'react-i18next';

export function OnboardingPage() {
  const { t } = useTranslation();
  const form = useForm({
    initialValues: { orgName: '', projectName: '', platform: 'javascript' },
    validate: {
      orgName: (v) => (v.trim().length < 2 ? 'Required' : null),
      projectName: (v) => (v.trim().length < 2 ? 'Required' : null),
    },
  });

  return (
    <Center mih="100vh" p="md">
      <Card shadow="sm" padding="xl" radius="md" withBorder w={520}>
        <form onSubmit={form.onSubmit((values) => console.warn('TODO', values))}>
          <Stack>
            <Title order={3}>{t('onboarding.title')}</Title>
            <TextInput label={t('onboarding.orgName')} {...form.getInputProps('orgName')} />
            <TextInput label={t('onboarding.projectName')} {...form.getInputProps('projectName')} />
            <Select
              label={t('onboarding.platform')}
              data={[
                { value: 'javascript', label: 'JavaScript / Browser' },
                { value: 'react', label: 'React' },
                { value: 'java-spring', label: 'Java / Spring Boot' },
              ]}
              {...form.getInputProps('platform')}
            />
            <Button type="submit" fullWidth>
              {t('onboarding.create')}
            </Button>
          </Stack>
        </form>
      </Card>
    </Center>
  );
}
