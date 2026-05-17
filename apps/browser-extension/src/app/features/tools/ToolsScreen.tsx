import { MUTATING_TOOLS } from '@arguslog/mcp-server/contract';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import type { ReactElement } from 'react';

import { callRawTool, listCatalogTools } from '../../../shared/domain/catalog';
import { ConfirmDialog } from '../../../shared/ui/components/ConfirmDialog';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Input,
  Page,
  Select,
  Textarea,
} from '../../../shared/ui/components/primitives';

type JsonSchema = Record<string, unknown>;

function setNestedValue(
  target: Record<string, unknown>,
  path: string[],
  value: unknown,
): Record<string, unknown> {
  if (path.length === 0) return target;
  const [head, ...rest] = path;
  if (!head) return target;
  return {
    ...target,
    [head]:
      rest.length === 0
        ? value
        : setNestedValue((target[head] as Record<string, unknown> | undefined) ?? {}, rest, value),
  };
}

function parseValue(type: unknown, value: string): unknown {
  if (type === 'integer' || type === 'number') {
    return value === '' ? undefined : Number(value);
  }
  if (type === 'boolean') {
    return value === 'true';
  }
  if (type === 'array') {
    return value
      .split(',')
      .map((item) => item.trim())
      .filter(Boolean);
  }
  return value;
}

function renderFields(
  schema: JsonSchema | undefined,
  values: Record<string, unknown>,
  onChange: (path: string[], value: unknown) => void,
  parentPath: string[] = [],
): ReactElement[] {
  const properties = (schema?.properties as Record<string, JsonSchema> | undefined) ?? {};
  return Object.entries(properties).map(([key, property]) => {
    const path = [...parentPath, key];
    const fieldId = `tool-field-${path.join('-')}`;
    const value = path.reduce<unknown>((acc, segment) => {
      if (acc && typeof acc === 'object' && segment in (acc as Record<string, unknown>)) {
        return (acc as Record<string, unknown>)[segment];
      }
      return undefined;
    }, values);

    if (property.type === 'object') {
      return (
        <div key={path.join('.')} className="space-y-2 rounded-xl border border-slate-800 p-3">
          <p className="text-sm font-medium text-white">{path.join('.')}</p>
          <div className="space-y-3">{renderFields(property, values, onChange, path)}</div>
        </div>
      );
    }

    return (
      <div key={path.join('.')} className="space-y-1">
        <label htmlFor={fieldId} className="text-xs uppercase tracking-wide text-slate-400">
          {path.join('.')}
        </label>
        {property.enum ? (
          <Select
            id={fieldId}
            value={String(value ?? '')}
            onChange={(event) => onChange(path, parseValue(property.type, event.target.value))}
          >
            <option value="">Select</option>
            {(property.enum as string[]).map((entry) => (
              <option key={entry} value={entry}>
                {entry}
              </option>
            ))}
          </Select>
        ) : (
          <Input
            id={fieldId}
            value={value === undefined ? '' : String(value)}
            onChange={(event) => onChange(path, parseValue(property.type, event.target.value))}
            placeholder={(property.description as string | undefined) ?? key}
          />
        )}
      </div>
    );
  });
}

export function ToolsScreen() {
  const toolsQuery = useQuery({
    queryKey: ['catalog-tools'],
    queryFn: listCatalogTools,
  });
  const [selectedToolName, setSelectedToolName] = useState<string | undefined>();
  const [formData, setFormData] = useState<Record<string, unknown>>({});
  const [confirmOpen, setConfirmOpen] = useState(false);

  useEffect(() => {
    if (!selectedToolName && toolsQuery.data?.[0]) {
      setSelectedToolName(toolsQuery.data[0].name);
    }
  }, [selectedToolName, toolsQuery.data]);

  const selectedTool = useMemo(
    () => toolsQuery.data?.find((tool) => tool.name === selectedToolName),
    [selectedToolName, toolsQuery.data],
  );

  const mutation = useMutation({
    mutationFn: () =>
      callRawTool(
        selectedToolName!,
        formData,
        MUTATING_TOOLS.includes(selectedToolName as (typeof MUTATING_TOOLS)[number]),
      ),
    onSuccess: () => setConfirmOpen(false),
  });

  return (
    <Page
      title="Advanced tools"
      subtitle="Generic schema-driven runner for any tool returned by tools/list."
    >
      <div className="grid gap-4 lg:grid-cols-[0.8fr,1.2fr]">
        <Card title="Tool catalog">
          <div className="space-y-2">
            {toolsQuery.data?.map((tool) => (
              <button
                key={tool.name}
                type="button"
                onClick={() => {
                  setSelectedToolName(tool.name);
                  setFormData({});
                }}
                className={`w-full rounded-xl border p-3 text-left ${
                  selectedToolName === tool.name
                    ? 'border-blue-400 bg-blue-500/10'
                    : 'border-slate-800 bg-slate-950/50'
                }`}
              >
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="font-medium text-white">{tool.title ?? tool.name}</p>
                    <p className="mt-1 text-sm text-slate-400">{tool.description}</p>
                  </div>
                  <Badge
                    tone={
                      MUTATING_TOOLS.includes(tool.name as (typeof MUTATING_TOOLS)[number])
                        ? 'danger'
                        : 'success'
                    }
                  >
                    {MUTATING_TOOLS.includes(tool.name as (typeof MUTATING_TOOLS)[number])
                      ? 'write'
                      : 'read'}
                  </Badge>
                </div>
              </button>
            ))}
          </div>
        </Card>

        <Card
          title={selectedTool?.title ?? selectedTool?.name ?? 'Tool runner'}
          actions={
            selectedTool ? (
              <Button
                onClick={() => {
                  if (
                    MUTATING_TOOLS.includes(selectedTool.name as (typeof MUTATING_TOOLS)[number])
                  ) {
                    setConfirmOpen(true);
                    return;
                  }
                  mutation.mutate();
                }}
              >
                Run tool
              </Button>
            ) : undefined
          }
        >
          {selectedTool ? (
            <div className="space-y-4">
              <div className="space-y-3">
                {renderFields(selectedTool.inputSchema, formData, (path, value) =>
                  setFormData((current) => setNestedValue(current, path, value)),
                )}
              </div>
              <div>
                <label
                  htmlFor="tool-result"
                  className="text-xs uppercase tracking-wide text-slate-400"
                >
                  Result
                </label>
                <Textarea
                  id="tool-result"
                  readOnly
                  rows={18}
                  value={mutation.data ? JSON.stringify(mutation.data, null, 2) : ''}
                  placeholder="Tool response will appear here."
                />
              </div>
            </div>
          ) : (
            <EmptyState
              title="No tool selected"
              description="Choose a tool from the catalog to generate an input form."
            />
          )}
        </Card>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="Confirm tool mutation"
        description={`Run mutating tool "${selectedToolName}" with the current payload?`}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={() => mutation.mutate()}
      />
    </Page>
  );
}
