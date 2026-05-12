/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_ARGUSLOG_DSN: string;
  readonly VITE_ARGUSLOG_RELEASE?: string;
  readonly VITE_ARGUSLOG_ENV?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
