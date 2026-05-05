import { Center, Stack, Text, Title } from '@mantine/core';

export function NotFoundPage() {
  return (
    <Center mih="100vh">
      <Stack align="center">
        <Title order={1}>404</Title>
        <Text c="dimmed">Not found.</Text>
      </Stack>
    </Center>
  );
}
