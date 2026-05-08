import type { EventPayload, PlatformAdapter } from '@arguslog/sdk-core';

import { SDK_VERSION } from './version.generated.js';

export class BrowserAdapter implements PlatformAdapter {
  readonly sdkName = 'arguslog.javascript';
  readonly sdkVersion = SDK_VERSION;
  readonly platform = 'javascript' as const;

  enrichEvent(event: EventPayload): void {
    if (typeof window !== 'undefined' && window.location) {
      event.request = {
        url: window.location.href,
        userAgent: window.navigator?.userAgent,
      };
    }
  }
}
