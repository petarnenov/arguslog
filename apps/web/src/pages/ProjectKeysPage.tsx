import { Card, Code, Stack, Title } from '@mantine/core';

export function ProjectKeysPage() {
  return (
    <Stack>
      <Title order={3}>DSN keys</Title>
      <Card withBorder padding="md">
        <Code block>https://&lt;publicKey&gt;@ingest.arguslog.example/&lt;projectId&gt;</Code>
      </Card>
    </Stack>
  );
}
