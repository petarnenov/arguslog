export { ArgusErrorBoundary } from './error-boundary.js';
export { useArgus } from './hooks.js';

export {
  init,
  captureException,
  captureMessage,
  setUser,
  setTag,
  setContext,
  addBreadcrumb,
  flush,
  getClient,
  type ArgusOptions,
  type Level,
  type EventPayload,
} from '@arguslog/sdk-browser';
