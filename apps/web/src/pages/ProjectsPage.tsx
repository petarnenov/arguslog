import { Card, Group, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router';

const PLACEHOLDER = [
  { slug: 'web-app', name: 'Web App', platform: 'javascript', issues: 0 },
  { slug: 'api', name: 'API', platform: 'java-spring', issues: 0 },
];

export function ProjectsPage() {
  const { orgSlug } = useParams();
  return (
    <Stack>
      <Title order={3}>Projects</Title>
      <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
        {PLACEHOLDER.map((p) => (
          <Card
            key={p.slug}
            component={Link}
            to={`/orgs/${orgSlug}/projects/${p.slug}/issues`}
            shadow="xs"
            padding="lg"
            radius="md"
            withBorder
          >
            <Group justify="space-between">
              <Title order={5}>{p.name}</Title>
              <Text size="xs" c="dimmed">
                {p.platform}
              </Text>
            </Group>
            <Text size="sm" c="dimmed">
              {p.issues} open issues
            </Text>
          </Card>
        ))}
      </SimpleGrid>
    </Stack>
  );
}
