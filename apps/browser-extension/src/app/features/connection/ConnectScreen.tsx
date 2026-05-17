import { Page } from '../../../shared/ui/components/primitives';

import { ConnectionForm } from './ConnectionForm';

export function ConnectScreen() {
  return (
    <Page
      title="Connect"
      subtitle="Authenticate with an Arguslog PAT, test connectivity, and build the capability snapshot."
    >
      <ConnectionForm />
    </Page>
  );
}
