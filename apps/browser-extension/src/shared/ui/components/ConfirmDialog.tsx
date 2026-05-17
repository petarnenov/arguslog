import { Button, Card } from './primitives';

export function ConfirmDialog(props: {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  cancelLabel?: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  if (!props.open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/70 p-4">
      <Card className="w-full max-w-md">
        <div className="space-y-4">
          <div>
            <h2 className="text-base font-semibold text-white">{props.title}</h2>
            <p className="mt-2 text-sm text-slate-300">{props.description}</p>
          </div>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={props.onCancel}>
              {props.cancelLabel ?? 'Cancel'}
            </Button>
            <Button variant="danger" onClick={props.onConfirm}>
              {props.confirmLabel ?? 'Confirm'}
            </Button>
          </div>
        </div>
      </Card>
    </div>
  );
}
