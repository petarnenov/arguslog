import { Card, Code, Stack, Title } from '@mantine/core';
import { useParams } from 'react-router';

export function IssueDetailPage() {
  const { issueId } = useParams();
  return (
    <Stack>
      <Title order={3}>Issue {issueId}</Title>
      <Card withBorder padding="md">
        <Code block>Stack trace will appear here once the API client is wired up.</Code>
      </Card>
    </Stack>
  );
}
