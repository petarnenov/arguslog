import { Page } from '../../../shared/ui/components/primitives';

import { ConnectionForm } from './ConnectionForm';
import { ConnectionHealthBadge } from './ConnectionHealthBadge';

export function ConnectScreen() {
  return (
    <Page
      title="Connect"
      subtitle="Authenticate with an Arguslog PAT, test connectivity, and build the capability snapshot."
    >
      <div className="space-y-4">
        <ConnectionHealthBadge />
        <ConnectionForm />
      </div>
    </Page>
  );
}
