import { Badge, Card, Group, Stack, Table, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';

export function IssuesPage() {
  const { t } = useTranslation();
  const { orgSlug, projectSlug } = useParams();

  // Placeholder data — will be replaced by useQuery on api-client
  const issues: { id: string; title: string; level: string; lastSeen: string; count: number }[] =
    [];

  if (issues.length === 0) {
    return (
      <Stack>
        <Title order={3}>{t('issues.title')}</Title>
        <Card withBorder padding="xl">
          <Text c="dimmed">{t('issues.empty')}</Text>
        </Card>
      </Stack>
    );
  }

  return (
    <Stack>
      <Title order={3}>{t('issues.title')}</Title>
      <Table highlightOnHover striped>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('issues.title')}</Table.Th>
            <Table.Th>{t('issues.level')}</Table.Th>
            <Table.Th>{t('issues.lastSeen')}</Table.Th>
            <Table.Th>{t('issues.occurrences')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {issues.map((issue) => (
            <Table.Tr key={issue.id}>
              <Table.Td>
                <Link to={`/orgs/${orgSlug}/projects/${projectSlug}/issues/${issue.id}`}>
                  {issue.title}
                </Link>
              </Table.Td>
              <Table.Td>
                <Badge color={issue.level === 'fatal' ? 'red' : 'orange'}>{issue.level}</Badge>
              </Table.Td>
              <Table.Td>{issue.lastSeen}</Table.Td>
              <Table.Td>
                <Group>{issue.count}</Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
