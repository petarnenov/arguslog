export { ArguslogErrorBoundary } from './error-boundary.js';
export { useArguslog } from './hooks.js';

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
  ArguslogClient,
  InvalidDsnError,
  parseDsn,
  type ArguslogOptions,
  type Breadcrumb,
  type EventPayload,
  type Level,
  type StackFrame,
  type User,
} from '@arguslog/sdk-browser';
