import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

import { listCatalogPrompts, getPromptText } from '../../../shared/domain/catalog';
import { Badge, Button, Card, EmptyState, Input, Page } from '../../../shared/ui/components/primitives';

export function PlaybooksScreen() {
  const promptsQuery = useQuery({
    queryKey: ['catalog-prompts'],
    queryFn: listCatalogPrompts,
  });
  const [selectedPrompt, setSelectedPrompt] = useState<string | undefined>();
  const [args, setArgs] = useState<Record<string, string>>({});

  useEffect(() => {
    if (!selectedPrompt && promptsQuery.data?.[0]) {
      setSelectedPrompt(promptsQuery.data[0].name);
    }
  }, [promptsQuery.data, selectedPrompt]);

  const promptDef = promptsQuery.data?.find((prompt) => prompt.name === selectedPrompt);
  const promptMutation = useMutation({
    mutationFn: () => getPromptText(selectedPrompt!, args),
  });

  return (
    <Page title="Playbook inspector" subtitle="Read-only view over MCP prompts/list and prompts/get.">
      <div className="grid gap-4 lg:grid-cols-[0.9fr,1.1fr]">
        <Card title="Available prompts">
          <div className="space-y-2">
            {promptsQuery.data?.map((prompt) => (
              <button
                key={prompt.name}
                type="button"
                onClick={() => {
                  setSelectedPrompt(prompt.name);
                  setArgs({});
                }}
                className={`w-full rounded-xl border p-3 text-left ${
                  selectedPrompt === prompt.name
                    ? 'border-blue-400 bg-blue-500/10'
                    : 'border-slate-800 bg-slate-950/40'
                }`}
              >
                <div className="flex items-center justify-between gap-3">
                  <p className="font-medium text-white">{prompt.title ?? prompt.name}</p>
                  <Badge>{prompt.arguments?.length ?? 0} args</Badge>
                </div>
                <p className="mt-1 text-sm text-slate-400">{prompt.description}</p>
              </button>
            ))}
            {!promptsQuery.data?.length ? (
              <EmptyState title="No prompts available" description="Refresh capabilities or reconnect if the prompt catalog is unavailable." />
            ) : null}
          </div>
        </Card>

        <Card title={promptDef?.title ?? 'Prompt body'} actions={
          promptDef ? (
            <Button variant="secondary" onClick={() => promptMutation.mutate()}>
              Render prompt
            </Button>
          ) : undefined
        }>
          {promptDef ? (
            <div className="space-y-4">
              {promptDef.arguments?.length ? (
                <div className="grid gap-3 md:grid-cols-2">
                  {promptDef.arguments.map((argument) => (
                    <Input
                      key={argument.name}
                      placeholder={argument.description ?? argument.name}
                      value={args[argument.name] ?? ''}
                      onChange={(event) =>
                        setArgs((current) => ({
                          ...current,
                          [argument.name]: event.target.value,
                        }))
                      }
                    />
                  ))}
                </div>
              ) : (
                <EmptyState title="No arguments required" description="This prompt can be rendered immediately." />
              )}

              {promptMutation.data ? (
                <article className="prose prose-invert max-w-none rounded-xl border border-slate-800 bg-slate-950/50 p-4 text-sm">
                  <ReactMarkdown remarkPlugins={[remarkGfm]}>{promptMutation.data.text}</ReactMarkdown>
                </article>
              ) : (
                <EmptyState title="Prompt body not rendered yet" description="Fill any required arguments and render the playbook to inspect its body." />
              )}
            </div>
          ) : (
            <EmptyState title="No prompt selected" description="Pick a prompt from the left column." />
          )}
        </Card>
      </div>
    </Page>
  );
}
